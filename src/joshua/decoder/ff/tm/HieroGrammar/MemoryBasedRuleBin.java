package joshua.decoder.ff.tm.HieroGrammar;

import java.util.ArrayList;
import java.util.PriorityQueue;
import joshua.decoder.Support;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.ff.tm.RuleCollection;


/** contain all rules with the same french side (and thus same arity) */
	public class MemoryBasedRuleBin	implements RuleCollection {
		protected int arity = 0;//number of non-terminals
		protected int[] french;
		
		protected boolean                    sorted      = false;
		protected ArrayList<Rule>            sortedRules = new ArrayList<Rule>();
	
		/**
		 * TODO: now, we assume this function will be called
		 * only after all the rules have been read; this
		 * method need to be synchronized as we will call
		 * this function only after the decoding begins;
		 * to avoid the synchronized method, we should call
		 * this once the grammar is finished
		 * what about the weights changed as in MERT??
		 */
		//public synchronized ArrayList<Rule> get_sorted_rules() {
		public ArrayList<Rule> getSortedRules() {
			if (! this.sorted) {
				//==use a priority queue to help sort
				PriorityQueue<Rule> t_heapRules = new PriorityQueue<Rule>(1, Rule.NegtiveCostComparator);
				for(Rule rule : sortedRules ){					
					t_heapRules.add(rule);
				}
				
				//==rearange the sortedRules based on t_heapRules
				this.sortedRules.clear();
				while (t_heapRules.size() > 0) {
					Rule t_r = (Rule) t_heapRules.poll();
					this.sortedRules.add(0, t_r);
				}
				this.sorted    = true;
			}
			return this.sortedRules;
		}
		
		public int[] getSourceSide() {
			return this.french;
		}
		
		
		public int getArity() {
			return this.arity;
		}
		
		
		protected void add_rule(Rule rule) {
			if (sortedRules.size()<=0) {//first time
				this.arity = rule.arity;
				this.french = rule.p_french; 
			}
			if (rule.arity != this.arity) {
				Support.write_log_line(	String.format("RuleBin: arity is not matching, old: %d; new: %d", this.arity, rule.arity),	Support.ERROR);
				return;
			}
			sortedRules.add(rule);
			sorted      = false;
			rule.p_french = this.french; //TODO: this will release the memory in each rule, but each rule still have a pointer to it
		}
	}