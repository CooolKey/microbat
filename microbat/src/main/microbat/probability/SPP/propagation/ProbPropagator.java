package microbat.probability.SPP.propagation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import debuginfo.NodeFeedbackPair;
import debuginfo.NodeFeedbacksPair;
import microbat.bytecode.ByteCode;
import microbat.bytecode.ByteCodeList;
import microbat.bytecode.OpcodeType;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;
import microbat.model.variable.Variable;
import microbat.probability.PropProbability;
import microbat.probability.SPP.ProbAggregateMethods;
import microbat.probability.SPP.ProbAggregator;

public class ProbPropagator {
	
	private final Trace trace;
	private final List<TraceNode> slicedTrace;
	
	private final Set<VarValue> correctVars;
	private final Set<VarValue> wrongVars;
	
	private final List<OpcodeType> unmodifiedType = new ArrayList<>();
	private List<NodeFeedbacksPair> feedbackRecords = new ArrayList<>();
	
	public ProbPropagator(Trace trace, List<TraceNode> slicedTrace, Set<VarValue> correctVars, Set<VarValue> wrongVars, List<NodeFeedbacksPair> feedbackRecords) {
		this.trace = trace;
		this.slicedTrace = slicedTrace;
		this.correctVars = correctVars;
		this.wrongVars = wrongVars;
		this.feedbackRecords = feedbackRecords;
		this.constructUnmodifiedOpcodeType();
	}
	
	public void propagate() {
		this.initProb();
		this.computeComputationalCost();
		this.forwardPropagate();
		this.backwardPropagate();
		this.combineProb();
	}
	
	/**
	 * Initialize the probability of each variables
	 * 
	 * Inputs are set to 0.95. <br>
	 * Outputs are set to 0.05. <br>
	 * Others are set to 0.5.
	 */
	public void initProb() {
		for (TraceNode node : this.trace.getExecutionList()) {
			for (VarValue readVar : node.getReadVariables()) {
				readVar.setAllProbability(PropProbability.UNCERTAIN);
				if (this.correctVars.contains(readVar)) {
					readVar.setForwardProb(PropProbability.CORRECT);
				}
				if (this.wrongVars.contains(readVar)) {
					readVar.setBackwardProb(PropProbability.WRONG);
				}
			}
			for (VarValue writeVar : node.getWrittenVariables()) {
				writeVar.setAllProbability(PropProbability.UNCERTAIN);
				if (this.correctVars.contains(writeVar)) {
					writeVar.setForwardProb(PropProbability.CORRECT);
				}
				if (this.wrongVars.contains(writeVar)) {
					writeVar.setBackwardProb(PropProbability.WRONG);
				}
			}
		}
	}
	
	private void forwardPropagate() {
//		this.computeMinOutputCost();
		for (TraceNode node : this.slicedTrace) {
			
			if (this.isFeedbackGiven(node)) {
				continue;
			}
			
			// Pass forward probability 
			this.passForwardProp(node);
						
			// We will ignore "this" variable
			List<VarValue> readVars = node.getReadVariables().stream().filter(var -> !var.isThisVariable()).toList();
			List<VarValue> writtenVars = node.getWrittenVariables().stream().filter(var -> !var.isThisVariable()).toList();
			
			// Skip propagation if either read or written variable is missing
			if (readVars.isEmpty() || writtenVars.isEmpty()) {
				continue;
			}
			
			// Average probability of read variables excluding "this" variable
			final double avgProb = readVars.stream().mapToDouble(var -> var.getForwardProb()).average().orElse(0.0d);
			final double drop = avgProb - PropProbability.UNCERTAIN;
			final double cost_factor = node.computationCost;
			
			final double result_prob = avgProb - drop * cost_factor;
			
			for (VarValue writtenVar : writtenVars) {
				if (this.isCorrect(writtenVar)) {
					writtenVar.setForwardProb(PropProbability.CORRECT);
				} else {
					writtenVar.setForwardProb(result_prob);
				}
			}
		}
	}
	
	private boolean isCorrect(final VarValue var) {
		return this.correctVars.contains(var);
	}
	
	private boolean isWrong(final VarValue var) {
		return this.wrongVars.contains(var);
	}
	
	private void passForwardProp(final TraceNode node) {
		// Receive the correctness propagation
		for (VarValue readVar : node.getReadVariables()) {
			if (this.isCorrect(readVar)) {
				readVar.setForwardProb(PropProbability.CORRECT);
			}
			VarValue dataDomVar = this.findDataDomVar(readVar, node);
			if (dataDomVar != null) {
				readVar.setForwardProb(dataDomVar.getForwardProb());
			} else {
				readVar.setForwardProb(PropProbability.UNCERTAIN);
			}
		}
	}
	
	private void backwardPropagate() {
		// Loop the execution list backward
		for (int order = this.slicedTrace.size()-1; order>=0; order--) {
			TraceNode node = this.slicedTrace.get(order);
			
			if (node.getOrder() == 6) {
				System.out.println();
			}
			// Initialize written variables probability
			this.passBackwardProp(node);
			
			// We will ignore "this" variable
			List<VarValue> readVars = node.getReadVariables().stream().filter(var -> !var.isThisVariable()).toList();
			List<VarValue> writtenVars = node.getWrittenVariables().stream().filter(var -> !var.isThisVariable()).toList();
			
			// Skip propagation if either read or written variable is missing
			if (readVars.isEmpty() || writtenVars.isEmpty()) {
				continue;
			}
			
			// Calculate the average probability of written variables excluding "this" variable
			final double avgProb = writtenVars.stream().mapToDouble(var -> var.getBackwardProb()).average().orElse(0.0d);
			final double gain = PropProbability.UNCERTAIN - avgProb;
			final double cost_factor = node.computationCost;
			node.setGain(gain * cost_factor);
			
			// Sum of computation cost of read variables excluding "this" variable
			final double sumOfCost = readVars.stream().mapToDouble(var -> var.computationalCost).sum();
			final double readVarCount = readVars.size();
			for (VarValue readVar : readVars) {
				if (this.isWrong(readVar)) {
					readVar.setBackwardProb(PropProbability.WRONG);
				} else {
					// If there are only one read variable, then it is the most suspicious
					double suspiciousness;
					if (readVars.size() == 1) {
						suspiciousness = 1.0;
					} else {
						// It is still possible that sumOfCost is zero
						// In this case, we distribute the wrongness evenly 
						suspiciousness = sumOfCost == 0.0d ?
											1 / readVarCount :
											1 - readVar.computationalCost / sumOfCost;
					}
					
					
					
					final double prob = avgProb + gain * cost_factor * suspiciousness;
					readVar.setBackwardProb(prob);
				}
			}
		}
	}
	
	private void passBackwardProp(final TraceNode node) {
		
		// Receive the wrongness propagation
		for (VarValue writtenVar : node.getWrittenVariables()) {
			
			if (this.isWrong(writtenVar)) {
				writtenVar.setBackwardProb(PropProbability.WRONG);
				continue;
			}
			
			// Different back propagation strategy for condition result
			if (node.isBranch() && writtenVar.equals(node.getConditionResult())) {
				/*
				 * Backward probability of condition result will be the average of
				 * all written variables that is under his control domination
				 * 
				 * We need to filter out those that does not contribute to the output
				 */
				final double avgWrittenProb = node.getControlDominatees().stream()
												  .filter(node_ -> this.slicedTrace.contains(node_))
												  .flatMap(node_ -> node_.getWrittenVariables().stream())
												  .mapToDouble(var -> var.getBackwardProb())
												  .average()
												  .orElse(PropProbability.UNCERTAIN);
				writtenVar.setBackwardProb(avgWrittenProb);
				continue;						  
			}
			
			List<TraceNode> dataDominatees = this.trace.findDataDependentee(node, writtenVar);
			final double maxProb = dataDominatees.stream()
					.filter(node_ -> this.slicedTrace.contains(node_))
					.flatMap(node_ -> node_.getReadVariables().stream())
					.filter(var -> var.equals(writtenVar))
					.mapToDouble(var -> var.getBackwardProb())
					.max()
					.orElse(PropProbability.UNCERTAIN);
			writtenVar.setBackwardProb(maxProb);
		}
	}

	private void combineProb() {
		for (TraceNode node : this.slicedTrace) {
			for (VarValue readVar : node.getReadVariables()) {
				double avgProb = (readVar.getForwardProb() + readVar.getBackwardProb())/2;
				readVar.setProbability(avgProb);
			}
			for (VarValue writtenVar : node.getWrittenVariables()) {
				double avgProb = (writtenVar.getForwardProb() + writtenVar.getBackwardProb())/2;
				writtenVar.setProbability(avgProb);
			}
		}
	}
	
	private VarValue findDataDomVar(final VarValue var, final TraceNode node) {
		TraceNode dataDominator = this.trace.findDataDependency(node, var);
		if (dataDominator != null) {
			for (VarValue writeVar : dataDominator.getWrittenVariables()) {
				if (writeVar.equals(var)) {
					return writeVar;
				}
			}
		}
		return null;
	}
	
	private boolean isFeedbackGiven(final TraceNode node) {
		for (NodeFeedbacksPair pair : this.feedbackRecords) {
			if (node.equals(pair.getNode())) {
				return true;
			}
		}
		return false;
	}
	
	private int countModifyOperation(final TraceNode node) {
		ByteCodeList byteCodeList = new ByteCodeList(node.getBytecode());
		int count = 0;
		for (ByteCode byteCode : byteCodeList) {
			if (!this.unmodifiedType.contains(byteCode.getOpcodeType())) {
				count+=1;
			}
		}
		return count;
	}
	
	public void computeComputationalCost() {
		
		// First count the computational operations for each step and normalize
		final long totalNodeCost = this.trace.getExecutionList().stream()
									   .mapToLong(node -> this.countModifyOperation(node))
									   .sum();
		
		this.trace.getExecutionList().stream()
									 .forEach(node -> node.computationCost =  this.countModifyOperation(node) / (double) totalNodeCost);
		
		// Init computational cost of all variable to 1.0
		this.trace.getExecutionList().stream().flatMap(node -> node.getReadVariables().stream()).forEach(var -> var.computationalCost = 0.0d);
		this.trace.getExecutionList().stream().flatMap(node -> node.getWrittenVariables().stream()).forEach(var -> var.computationalCost = 0.0d);
									 
		double maxVarCost = 0.0f;
		for (TraceNode node : this.trace.getExecutionList()) {
			
			// Skip if there are no read variable (do not count "this" variable)
			List<VarValue> readVars = new ArrayList<>();
			readVars.addAll(node.getReadVariables());
			readVars.removeIf(var -> var.isThisVariable());
			if (readVars.size() == 0) {
				continue;
			}
			
			// Inherit computational cost
			for (VarValue readVar : node.getReadVariables()) {
				final VarValue dataDomVar = this.findDataDomVar(readVar, node);
				if (dataDomVar != null) {
					readVar.computationalCost = dataDomVar.computationalCost;
				}
			}
			
			// Sum up the cost of all read variable, excluding "this" variable
			final double cumulatedCost = node.getReadVariables().stream().filter(var -> !var.isThisVariable())
					.mapToDouble(var -> var.computationalCost)
					.sum();
			final double optCost = node.computationCost;
			final double cost = cumulatedCost + optCost;
			
			// Assign computational cost to written variable, excluding "this" variable
			node.getWrittenVariables().stream().filter(var -> !var.isThisVariable()).forEach(var -> var.computationalCost = cost);
			maxVarCost = Math.max(cost, maxVarCost);
		}
		final double maxVarCost_ = maxVarCost;
		System.out.println("Max Var Cost: " + maxVarCost_);
		
		trace.getExecutionList().stream().flatMap(node -> node.getReadVariables().stream()).forEach(var -> var.computationalCost /= maxVarCost_);
		trace.getExecutionList().stream().flatMap(node -> node.getWrittenVariables().stream()).forEach(var -> var.computationalCost /= maxVarCost_);
	}
	
	private void constructUnmodifiedOpcodeType() {
		this.unmodifiedType.add(OpcodeType.LOAD_CONSTANT);
		this.unmodifiedType.add(OpcodeType.LOAD_FROM_ARRAY);
		this.unmodifiedType.add(OpcodeType.LOAD_VARIABLE);
		this.unmodifiedType.add(OpcodeType.STORE_INTO_ARRAY);
		this.unmodifiedType.add(OpcodeType.STORE_VARIABLE);
		this.unmodifiedType.add(OpcodeType.RETURN);
		this.unmodifiedType.add(OpcodeType.GET_FIELD);
		this.unmodifiedType.add(OpcodeType.GET_STATIC_FIELD);
		this.unmodifiedType.add(OpcodeType.PUT_FIELD);
		this.unmodifiedType.add(OpcodeType.PUT_STATIC_FIELD);
		this.unmodifiedType.add(OpcodeType.INVOKE);
	}
}
