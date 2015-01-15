package joshua.decoder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import joshua.corpus.Vocabulary;
import joshua.decoder.ff.FeatureVector;
import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.ArityPhrasePenaltyFF;
import joshua.decoder.ff.LabelCombinationFF;
import joshua.decoder.ff.LabelSubstitutionFF;
import joshua.decoder.ff.OOVFF;
import joshua.decoder.ff.PhraseModelFF;
import joshua.decoder.ff.PhrasePenaltyFF;
import joshua.decoder.ff.RuleFF;
import joshua.decoder.ff.RuleLengthFF;
import joshua.decoder.ff.SourcePathFF;
import joshua.decoder.ff.WordPenaltyFF;
import joshua.decoder.ff.fragmentlm.FragmentLMFF;
import joshua.decoder.ff.lm.KenLMFF;
import joshua.decoder.ff.lm.LanguageModelFF;
import joshua.decoder.ff.lm.NGramLanguageModel;
import joshua.decoder.ff.lm.berkeley_lm.LMGrammarBerkeley;
import joshua.decoder.ff.lm.kenlm.jni.KenLM;
import joshua.decoder.ff.phrase.DistortionFF;
import joshua.decoder.ff.similarity.EdgePhraseSimilarityFF;
import joshua.decoder.ff.tm.Grammar;
import joshua.decoder.ff.tm.hash_based.MemoryBasedBatchGrammar;
import joshua.decoder.ff.tm.packed.PackedGrammar;
import joshua.decoder.io.TranslationRequest;
import joshua.decoder.phrase.PhraseTable;
import joshua.decoder.segment_file.Sentence;
import joshua.util.FileUtility;
import joshua.util.Regex;
import joshua.util.io.LineReader;

/**
 * This class handles decoder initialization and the complication introduced by multithreading.
 * 
 * After initialization, the main entry point to the Decoder object is
 * decodeAll(TranslationRequest), which returns a set of Translation objects wrapped in an iterable
 * Translations object. It is important that we support multithreading both (a) across the sentences
 * within a request and (b) across requests, in a round-robin fashion. This is done by maintaining a
 * fixed sized concurrent thread pool. When a new request comes in, a RequestHandler thread is
 * launched. This object reads iterates over the request's sentences, obtaining a thread from the
 * thread pool, and using that thread to decode the sentence. If a decoding thread is not available,
 * it will block until one is in a fair (FIFO) manner. This maintains fairness across requests so
 * long as each request only requests thread when it has a sentence ready.
 * 
 * A decoding thread is handled by DecoderThread and launched from DecoderThreadRunner. The purpose
 * of the runner is to record where to place the translated sentence when it is done (i.e., which
 * Translations object). Translations itself is an iterator whose next() call blocks until the next
 * translation is available.
 * 
 * @author Matt Post <post@cs.jhu.edu>
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @author wren ng thornton <wren@users.sourceforge.net>
 * @author Lane Schwartz <dowobeha@users.sourceforge.net>
 */
public class Decoder {

  private final JoshuaConfiguration joshuaConfiguration;

  /*
   * Many of these objects themselves are global objects. We pass them in when constructing other
   * objects, so that they all share pointers to the same object. This is good because it reduces
   * overhead, but it can be problematic because of unseen dependencies (for example, in the
   * Vocabulary shared by language model, translation grammar, etc).
   */
  private List<Grammar> grammars;
  private ArrayList<FeatureFunction> featureFunctions;
  private ArrayList<NGramLanguageModel> languageModels;

  /*
   * A sorted list of the feature names (so they can be output in the order they were read in)
   */
  public static ArrayList<String> feature_names = new ArrayList<String>();

  /* The feature weights. */
  public static FeatureVector weights;

  public static int VERBOSE = 1;

  private BlockingQueue<DecoderThread> threadPool = null;

  public static boolean usingNonlocalFeatures = false;

  // ===============================================================
  // Constructors
  // ===============================================================

  /**
   * Constructor method that creates a new decoder using the specified configuration file.
   * 
   * @param configFile Name of configuration file.
   */
  public Decoder(JoshuaConfiguration joshuaConfiguration, String configFile) {

    this(joshuaConfiguration);
    this.initialize(configFile);
  }

  /**
   * Factory method that creates a new decoder using the specified configuration file.
   * 
   * @param configFile Name of configuration file.
   */
  public static Decoder createDecoder(String configFile) {
    JoshuaConfiguration joshuaConfiguration = new JoshuaConfiguration();
    return new Decoder(joshuaConfiguration, configFile);
  }

  /**
   * Constructs an uninitialized decoder for use in testing.
   * <p>
   * This method is private because it should only ever be called by the
   * {@link #getUninitalizedDecoder()} method to provide an uninitialized decoder for use in
   * testing.
   */
  private Decoder(JoshuaConfiguration joshuaConfiguration) {
    this.joshuaConfiguration = joshuaConfiguration;
    this.grammars = new ArrayList<Grammar>();
    this.threadPool = new ArrayBlockingQueue<DecoderThread>(
        this.joshuaConfiguration.num_parallel_decoders, true);
  }

  /**
   * Gets an uninitialized decoder for use in testing.
   * <p>
   * This method is called by unit tests or any outside packages (e.g., MERT) relying on the
   * decoder.
   */
  static public Decoder getUninitalizedDecoder(JoshuaConfiguration joshuaConfiguration) {
    return new Decoder(joshuaConfiguration);
  }

  // ===============================================================
  // Public Methods
  // ===============================================================

  /**
   * This class is responsible for getting sentences from the TranslationRequest and procuring a
   * DecoderThreadRunner to translate it. Each call to decodeAll(TranslationRequest) launches a
   * thread that will read the request's sentences, obtain a DecoderThread to translate them, and
   * then place the Translation in the appropriate place.
   * 
   * @author Matt Post <post@cs.jhu.edu>
   * 
   */
  private class RequestHandler extends Thread {
    /* Source of sentences to translate. */
    private final TranslationRequest request;

    /* Where to put translated sentences. */
    private final Translations response;

    RequestHandler(TranslationRequest request, Translations response) {
      this.request = request;
      this.response = response;
    }

    @Override
    public void run() {
      /*
       * Repeatedly get an input sentence, wait for a DecoderThread, and then start a new thread to
       * translate the sentence. We start a new thread (via DecoderRunnerThread) as opposed to
       * blocking, so that the RequestHandler can go on to the next sentence in this request, which
       * allows parallelization across the sentences of the request.
       */
      for (;;) {
        Sentence sentence = request.next();
        if (sentence == null) {
          response.finish();
          break;
        }

        // This will block until a DecoderThread becomes available.
        DecoderThread thread = Decoder.this.getThread();
        new DecoderThreadRunner(thread, sentence, response).start();
      }
    }
  }

  /**
   * Retrieve a thread from the thread pool, blocking until one is available. The blocking occurs in
   * a fair fashion (i.e,. FIFO across requests).
   * 
   * @return a thread that can be used for decoding.
   */
  public DecoderThread getThread() {
    try {
      return threadPool.take();
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }

  /**
   * This class handles running a DecoderThread (which takes care of the actual translation of an
   * input Sentence, returning a Translation object when its done). This is done in a thread so as
   * not to tie up the RequestHandler that launched it, freeing it to go on to the next sentence in
   * the TranslationRequest, in turn permitting parallelization across the sentences of a request.
   * 
   * When the decoder thread is finshed, the Translation object is placed in the correct place in
   * the corresponding Translations object that was returned to the caller of
   * Decoder.decodeAll(TranslationRequest).
   * 
   * @author Matt Post <post@cs.jhu.edu>
   */
  private class DecoderThreadRunner extends Thread {

    private final DecoderThread decoderThread;
    private final Sentence sentence;
    private final Translations translations;

    DecoderThreadRunner(DecoderThread thread, Sentence sentence, Translations translations) {
      this.decoderThread = thread;
      this.sentence = sentence;
      this.translations = translations;
    }

    @Override
    public void run() {
      /*
       * Use the thread to translate the sentence. Then record the translation with the
       * corresponding Translations object, and return the thread to the pool.
       */
      try {
        Translation translation = decoderThread.translate(this.sentence);
        translations.record(translation);

        /*
         * This is crucial! It's what makes the thread available for the next sentence to be
         * translated.
         */
        threadPool.put(decoderThread);
      } catch (InterruptedException e) {
        // TODO Auto-generated catch block
        System.err
            .println("* WARNING: I encountered an error trying to return the decoder thread.");
        e.printStackTrace();
      } catch (RuntimeException e) {
        System.err.println(String.format(
            "* Decoder: fatal uncaught runtime exception on sentence %d: %s", sentence.id(),
            e.getMessage()));
        e.printStackTrace();
        System.exit(1);
      }
    }
  }

  /**
   * This function is the main entry point into the decoder. It translates all the sentences in a
   * (possibly boundless) set of input sentences. Each request launches its own thread to read the
   * sentences of the request.
   * 
   * @param request
   * @return an iterable set of Translation objects
   */
  public Translations decodeAll(TranslationRequest request) {
    Translations translations = new Translations(request);

    new RequestHandler(request, translations).start();

    return translations;
  }

  /**
   * We can also just decode a single sentence.
   * 
   * @param sentence
   * @return The translated sentence
   */
  public Translation decode(Sentence sentence) {
    // Get a thread.

    try {
      DecoderThread thread = threadPool.take();
      Translation translation = thread.translate(sentence);
      threadPool.put(thread);

      return translation;

    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    return null;
  }

  public void cleanUp() {
    for (DecoderThread thread : threadPool) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
  }

  public static void writeConfigFile(double[] newWeights, String template, String outputFile,
      String newDiscriminativeModel) {
    try {
      int columnID = 0;

      BufferedWriter writer = FileUtility.getWriteFileStream(outputFile);
      LineReader reader = new LineReader(template);
      try {
        for (String line : reader) {
          line = line.trim();
          if (Regex.commentOrEmptyLine.matches(line) || line.indexOf("=") != -1) {
            // comment, empty line, or parameter lines: just copy
            writer.write(line);
            writer.newLine();

          } else { // models: replace the weight
            String[] fds = Regex.spaces.split(line);
            StringBuffer newSent = new StringBuffer();
            if (!Regex.floatingNumber.matches(fds[fds.length - 1])) {
              throw new IllegalArgumentException("last field is not a number; the field is: "
                  + fds[fds.length - 1]);
            }

            if (newDiscriminativeModel != null && "discriminative".equals(fds[0])) {
              newSent.append(fds[0]).append(' ');
              newSent.append(newDiscriminativeModel).append(' ');// change the
                                                                 // file name
              for (int i = 2; i < fds.length - 1; i++) {
                newSent.append(fds[i]).append(' ');
              }
            } else {// regular
              for (int i = 0; i < fds.length - 1; i++) {
                newSent.append(fds[i]).append(' ');
              }
            }
            if (newWeights != null)
              newSent.append(newWeights[columnID++]);// change the weight
            else
              newSent.append(fds[fds.length - 1]);// do not change

            writer.write(newSent.toString());
            writer.newLine();
          }
        }
      } finally {
        reader.close();
        writer.close();
      }

      if (newWeights != null && columnID != newWeights.length) {
        throw new IllegalArgumentException("number of models does not match number of weights");
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // ===============================================================
  // Initialization Methods
  // ===============================================================

  /**
   * Initialize all parts of the JoshuaDecoder.
   * 
   * @param configFile File containing configuration options
   * @return An initialized decoder
   */
  public Decoder initialize(String configFile) {
    try {

      long pre_load_time = System.currentTimeMillis();

      /*
       * Weights can be listed in a separate file (denoted by parameter "weights-file") or directly
       * in the Joshua config file. Config file values take precedent.
       */
      Decoder.weights = this.readWeights(joshuaConfiguration.weights_file);

      // Keeps track of the translation model owners that are seen while initializing
      // so that they can be added as features later.
      HashSet<String> tmOwnersSeen = new HashSet<String>();

      for (int i = 0; i < joshuaConfiguration.weights.size(); i++) {
        String pair[] = joshuaConfiguration.weights.get(i).split("\\s+");

        /* Sanity check for old-style unsupported feature invocations. */
        if (pair.length != 2) {
          System.err.println("FATAL: Invalid feature weight line found in config file.");
          System.err
              .println(String.format("The line was '%s'", joshuaConfiguration.weights.get(i)));
          System.err
              .println("You might be using an old version of the config file that is no longer supported");
          System.err
              .println("Check joshua-decoder.org or email joshua_support@googlegroups.com for help");
          System.exit(17);
        }

        feature_names.add(pair[0]);
        weights.put(pair[0], Float.parseFloat(pair[1]));
      }

      if (joshuaConfiguration.show_weights_and_quit) {
        for (String key : Decoder.feature_names) {
          System.out.println(String.format("%s %.5f", key, weights.get(key)));
        }
        System.exit(0);
      }

      if (!weights.containsKey("BLEU"))
        Decoder.weights.put("BLEU", 0.0f);

      int num_dense = 0;
      for (String feature : feature_names)
        if (FeatureVector.isDense(feature))
          num_dense++;

      Decoder.LOG(1, String.format("Read %d sparse and %d dense weights", weights.size()
          - num_dense, num_dense));

      // Do this before loading the grammars and the LM.
      this.featureFunctions = new ArrayList<FeatureFunction>();

      // Initialize and load grammars.
      this.initializeTranslationGrammars(tmOwnersSeen);
      // TODO: Remove at some point, grammar loading happens through feature functions
      // and this does not make sense anymore
      Decoder.LOG(1, String.format("Grammar loading took: %d seconds.",
          (System.currentTimeMillis() - pre_load_time) / 1000));

      // Initialize the LM.
      initializeLanguageModels();

      // Initialize the features: requires that LM model has been initialized.
      this.initializeFeatureFunctions(tmOwnersSeen);
      // Initialize the TM owners. This can only be done after the grammars have been
      // initialized
      this.intializeTMOwners(tmOwnersSeen);

      // Sort the TM grammars (needed to do cube pruning)
      if (joshuaConfiguration.amortized_sorting) {
        Decoder.LOG(1, "Grammar sorting happening lazily on-demand.");
      } else {
        long pre_sort_time = System.currentTimeMillis();
        for (Grammar grammar : this.grammars) {
          grammar.sortGrammar(this.featureFunctions);
        }
        Decoder.LOG(1, String.format("Grammar sorting took %d seconds.",
            (System.currentTimeMillis() - pre_sort_time) / 1000));
      }

      // Create the threads
      for (int i = 0; i < joshuaConfiguration.num_parallel_decoders; i++) {
        this.threadPool.put(new DecoderThread(this.grammars, Decoder.weights,
            this.featureFunctions, joshuaConfiguration));
      }

    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return this;
  }

  /**
   * Retained to maintain backward compatibility Uses the lm lines in the Joshua config file which
   * are not defined as feature functions to create new LMs
   * 
   * @param args
   * @throws IOException
   */
  private void initializeLanguageModels() throws IOException {

    if (joshuaConfiguration.lms.size() > 0) {
      Decoder.LOG(1, "You seem to be using an old version of the Joshua config file");
      Decoder.LOG(1, "Language models should be defined as regular feature functions.");

      // Only initialize if necessary
      if (this.languageModels == null) {
        this.languageModels = new ArrayList<NGramLanguageModel>();
      }
      // lm = kenlm 5 0 0 100 file
      for (String lmLine : joshuaConfiguration.lms) {
        String[] tokens = lmLine.trim().split("\\s+");

        HashMap<String, String> argMap = new HashMap<String, String>();
        argMap.put("lm_type", tokens[0]);
        argMap.put("lm_order", tokens[1]);
        argMap.put("minimizing", tokens[2]);
        argMap.put("lm_file", tokens[5]);
        initializeLanguageModel(argMap);
      }
    }
  }

  /**
   * Initializes a language model and adds it as a feature
   * 
   * @param argMap A map of arguments supplied top the lm feature function throught the Joshua
   *          config file
   */
  private void initializeLanguageModel(HashMap<String, String> argMap) {
    if (this.languageModels == null) {
      this.languageModels = new ArrayList<NGramLanguageModel>();
    }
    String lm_type = argMap.get("lm_type");
    int lm_order = Integer.parseInt(argMap.get("lm_order"));
    boolean minimizing = Boolean.parseBoolean(argMap.get("minimizing"));
    String lm_file = argMap.get("lm_file");

    if (lm_type.equals("kenlm")) {
      KenLM lm = new KenLM(lm_order, lm_file, minimizing);
      this.languageModels.add(lm);
      Vocabulary.registerLanguageModel(lm);
      Vocabulary.id(joshuaConfiguration.default_non_terminal);
      addLMFeature(lm);

    } else if (lm_type.equals("berkeleylm")) {
      LMGrammarBerkeley lm = new LMGrammarBerkeley(lm_order, lm_file);
      this.languageModels.add(lm);
      Vocabulary.registerLanguageModel(lm);
      Vocabulary.id(joshuaConfiguration.default_non_terminal);
      addLMFeature(lm);

    } else if (lm_type.equals("none")) {
      ; // do nothing

    } else {
      Decoder.LOG(1, "WARNING: using built-in language model; you probably didn't intend this");
      Decoder.LOG(1, "Valid lm types are 'kenlm', 'berkeleylm', 'none'");
    }
  }

  private void addLMFeature(NGramLanguageModel lm) {
    if (lm instanceof KenLM && lm.isMinimizing()) {
      this.featureFunctions.add(new KenLMFF(weights, (KenLM) lm, joshuaConfiguration));
    } else {
      this.featureFunctions.add(new LanguageModelFF(weights, lm, joshuaConfiguration));
    }
  }

  /**
   * Initializes translation grammars Retained for backward compatibility
   * 
   * @param ownersSeen Records which PhraseModelFF's have been instantiated (one is needed for each
   *          owner)
   * @throws IOException
   */
  private void initializeTranslationGrammars(HashSet<String> ownersSeen) throws IOException {

    if (joshuaConfiguration.tms.size() > 0) {

      Decoder.LOG(1, "You seem to be using an old version of the Joshua config file");
      Decoder.LOG(1, "Translation models should be defined as regular feature functions.");

      // tm = {thrax/hiero,packed,samt} OWNER LIMIT FILE
      for (String tmLine : joshuaConfiguration.tms) {

        String[] tokens = tmLine.trim().split("\\s+");

        String tm_format = tokens[0];
        String tm_owner = tokens[1];
        int span_limit = Integer.parseInt(tokens[2]);
        String tm_file = tokens[3];

        Grammar grammar = null;
        if (tm_format.equals("packed") || new File(tm_file).isDirectory()) {
          try {
            grammar = new PackedGrammar(tm_file, span_limit, tm_owner, joshuaConfiguration);
          } catch (FileNotFoundException e) {
            System.err.println(String.format("Couldn't load packed grammar from '%s'", tm_file));
            System.err.println("Perhaps it doesn't exist, or it may be an old packed file format.");
            System.exit(2);
          }

        } else if (tm_format.equals("phrase")) {

          joshuaConfiguration.search_algorithm = "stack";
          grammar = new PhraseTable(tm_file, tm_owner, joshuaConfiguration);

        } else {
          // thrax, hiero, samt
          grammar = new MemoryBasedBatchGrammar(tm_format, tm_file, tm_owner,
              joshuaConfiguration.default_non_terminal, span_limit, joshuaConfiguration);
        }

        this.grammars.add(grammar);

        // Record the owner so we can create a feature function for her.
        ownersSeen.add(tm_owner);

      }
    }
  }

  /**
   * 
   * @param ownersSeen Records which PhraseModelFF's have been instantiated (one is needed for each
   *          owner)
   * @throws IOException
   */
  private void initializeTranslationGrammar(HashMap<String, String> argMap,
      HashSet<String> ownersSeen) throws IOException {

    // tm_format = {thrax/hiero,packed,samt} OWNER LIMIT FILE
    
    String tm_format = argMap.get("tm_format");
    String tm_owner = argMap.get("tm_owner");
    int span_limit = Integer.parseInt(argMap.get("span_limit"));
    String tm_file = argMap.get("tm_file");

    Grammar grammar = null;
    if (tm_format.equals("packed") || new File(tm_file).isDirectory()) {
      try {
        grammar = new PackedGrammar(tm_file, span_limit, tm_owner, joshuaConfiguration);
      } catch (FileNotFoundException e) {
        System.err.println(String.format("Couldn't load packed grammar from '%s'", tm_file));
        System.err.println("Perhaps it doesn't exist, or it may be an old packed file format.");
        System.exit(2);
      }

    } else if (tm_format.equals("phrase")) {

      joshuaConfiguration.search_algorithm = "stack";
      grammar = new PhraseTable(tm_file, tm_owner, joshuaConfiguration);

    } else {
      // thrax, hiero, samt
      grammar = new MemoryBasedBatchGrammar(tm_format, tm_file, tm_owner,
          joshuaConfiguration.default_non_terminal, span_limit, joshuaConfiguration);
    }

    this.grammars.add(grammar);

    // Record the owner so we can create a feature function for her.
    ownersSeen.add(tm_owner);

  }

  /**
   * Create and add a feature function for each tm owner, the first time we see each owner. Warning!
   * This needs to be done *after* initializing the grammars, in case there is a packed grammar,
   * since it resets the vocabulary.
   * 
   * @param ownersSeen A translation model owner
   * @throws IOException
   */
  private void intializeTMOwners(HashSet<String> ownersSeen) throws IOException {

    if (ownersSeen.size() != 0) {
      for (String owner : ownersSeen) {
        this.featureFunctions.add(new PhraseModelFF(weights, owner));
      }
    } else {
      Decoder.LOG(1, "* WARNING: no grammars supplied!  Supplying dummy glue grammar.");
      // TODO: this should initialize the grammar dynamically so that the goal
      // symbol and default
      // non terminal match
      MemoryBasedBatchGrammar glueGrammar = new MemoryBasedBatchGrammar("thrax", String.format(
          "%s/data/glue-grammar", System.getenv().get("JOSHUA")), "glue",
          joshuaConfiguration.default_non_terminal, -1, joshuaConfiguration);
      this.grammars.add(glueGrammar);
    }

    Decoder.LOG(1, String.format("Memory used %.1f MB",
        ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000000.0)));
  }

  /*
   * This function reads the weights for the model. Feature names and their weights are listed one
   * per line in the following format:
   * 
   * FEATURE_NAME WEIGHT
   */
  private FeatureVector readWeights(String fileName) {
    FeatureVector weights = new FeatureVector();

    if (fileName.equals(""))
      return new FeatureVector();

    try {
      LineReader lineReader = new LineReader(fileName);

      for (String line : lineReader) {
        line = line.replaceAll("\\s+", " ");

        if (line.equals("") || line.startsWith("#") || line.startsWith("//")
            || line.indexOf(' ') == -1)
          continue;

        String tokens[] = line.split("\\s+");
        String feature = tokens[0];
        Float value = Float.parseFloat(tokens[1]);

        weights.put(feature, value);
        feature_names.add(feature);
      }
    } catch (FileNotFoundException ioe) {
      System.err.println("* FATAL: Can't find weights-file '" + fileName + "'");
      System.exit(1);
    } catch (IOException ioe) {
      System.err.println("* FATAL: Can't read weights-file '" + fileName + "'");
      ioe.printStackTrace();
      System.exit(1);
    }

    Decoder.LOG(1, String.format("Read %d weights from file '%s'", weights.size(), fileName));

    return weights;
  }

  /**
   * Feature functions are instantiated with a line of the form
   * 
   * <pre>
   *   feature_function = FEATURE OPTIONS
   * </pre>
   * 
   * Weights for features are listed separately.
   * 
   * @param tmOwnersSeen
   * @throws IOException
   * 
   */
  private void initializeFeatureFunctions(HashSet<String> tmOwnersSeen) throws IOException {

    usingNonlocalFeatures = true;

    for (String featureLine : joshuaConfiguration.features) {

      // Get rid of the leading crap.
      featureLine = featureLine.replaceFirst("^feature_function\\s*=\\s*", "");

      String fields[] = featureLine.split("\\s+");
      String featureName = fields[0];
      String feature = featureName.toLowerCase();

      if (feature.equals("latticecost") || feature.equals("sourcepath")) {
        this.featureFunctions.add(new SourcePathFF(Decoder.weights));
      }

      // allows language models to be used as feature functions with arguments
      else if (feature.equals("languagemodel")) {
        // Add some point, all feature functions should accept arguments this way
        String rawArgs = "";
        for (int i = 1; i < fields.length; i++) {
          rawArgs += fields[i] + " ";
        }
        initializeLanguageModel(parseFeatureArgs(rawArgs.trim()));
      }

      // allows translation models to be used as feature functions with arguments
      else if (feature.equals("translationmodel")) {
        String rawArgs = "";
        for (int i = 1; i < fields.length; i++) {
          rawArgs += fields[i] + " ";
        }
        initializeTranslationGrammar(parseFeatureArgs(rawArgs.trim()), tmOwnersSeen);
      }

      else if (feature.equals("arityphrasepenalty") || feature.equals("aritypenalty")) {
        String owner = fields[1];
        int startArity = Integer.parseInt(fields[2].trim());
        int endArity = Integer.parseInt(fields[3].trim());

        this.featureFunctions.add(new ArityPhrasePenaltyFF(weights, String.format("%s %d %d",
            owner, startArity, endArity)));

      } else if (feature.equals("wordpenalty")) {
        this.featureFunctions.add(new WordPenaltyFF(weights));

      } else if (feature.equals("oovpenalty")) {
        this.featureFunctions.add(new OOVFF(weights, joshuaConfiguration));

      } else if (feature.equals("rulelength")) {
        this.featureFunctions.add(new RuleLengthFF(weights));

      } else if (feature.equals("edgephrasesimilarity")) {
        String host = fields[1].trim();
        int port = Integer.parseInt(fields[2].trim());

        try {
          this.featureFunctions.add(new EdgePhraseSimilarityFF(weights, host, port));

        } catch (Exception e) {
          e.printStackTrace();
          System.exit(1);
        }

      } else if (feature.equals("phrasemodel") || feature.equals("tm")) {
        String owner = fields[1].trim();
        String index = fields[2].trim();
        Float weight = Float.parseFloat(fields[3]);

        weights.put(String.format("tm_%s_%s", owner, index), weight);

      } else if (feature.equals("fragmentlm")) {
        this.featureFunctions.add(new FragmentLMFF(Decoder.weights, featureLine));

      } else if (feature.equals("rule")) {
        this.featureFunctions.add(new RuleFF(Decoder.weights, featureLine));

      } else if (feature.equals("phrasepenalty")) {
        this.featureFunctions.add(new PhrasePenaltyFF(Decoder.weights, featureLine));

      } else if (feature.equals(LabelCombinationFF.getLowerCasedFeatureName())) {
        this.featureFunctions.add(new LabelCombinationFF(weights));

      } else if (feature.equals(LabelSubstitutionFF.getLowerCasedFeatureName())) {
        this.featureFunctions.add(new LabelSubstitutionFF(weights));

      } else if (feature.equals("distortion")) {
        this.featureFunctions.add(new DistortionFF(weights));

      } else {
        try {
          Class<?> clas = Class.forName(String.format("joshua.decoder.ff.%sFF", featureName));
          Constructor<?> constructor = clas.getConstructor(FeatureVector.class, String[].class);
          this.featureFunctions.add((FeatureFunction) constructor.newInstance(weights, fields));
        } catch (Exception e) {
          e.printStackTrace();
          System.err.println("* WARNING: invalid feature '" + featureLine + "'");
          System.exit(1);
        }
      }
    }

    for (FeatureFunction feature : featureFunctions) {
      Decoder.LOG(1, String.format("FEATURE: %s", feature.logString()));
    }
  }

  /**
   * Parses the arguments passed to a feature function in the Joshua config file TODO: Replace this
   * with a proper CLI library at some point Expects key value pairs in the form : -argname value
   * Any key without a value is added with an empty string as value Multiple values for the same key
   * are not parsed. The first one is used.
   * 
   * @param rawArgs A string with the raw arguments and their names
   * @return A hash with the keys and the values of the string
   */
  private HashMap<String, String> parseFeatureArgs(String rawArgs) {
    HashMap<String, String> parsedArgs = new HashMap<String, String>();
    String[] args = rawArgs.split("\\s+");
    boolean lookingForValue = false;
    String currentKey = "";
    for (int i = 0; i < args.length; i++) {
      
      Pattern argKeyPattern = Pattern.compile("^-[a-zA-Z]\\S+");
      Matcher argKey = argKeyPattern.matcher(args[i]);
      if (argKey.find()) {
        // This is a key
        // First check to see if there is a key that is waiting to be written
        if (lookingForValue) {
          // This is a key with no specified value
          parsedArgs.put(currentKey, "");
        }
        // Now store the new key and look for its value
        currentKey = args[i].substring(1);
        lookingForValue = true;
      } else {
        // This is a value
        if (lookingForValue) {
          parsedArgs.put(currentKey, args[i]);
          lookingForValue = false;
        }
      }
    }
    return parsedArgs;
  }

  public static boolean VERBOSE(int i) {
    return i <= VERBOSE;
  }

  public static void LOG(int i, String msg) {
    if (VERBOSE(i))
      System.err.println(msg);
  }

}
