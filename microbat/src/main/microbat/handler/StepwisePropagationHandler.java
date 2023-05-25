package microbat.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import debuginfo.DebugInfo;
import debuginfo.NodeFeedbacksPair;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;
import microbat.probability.SPP.SPP;
import microbat.probability.SPP.pathfinding.ActionPath;
import microbat.recommendation.ChosenVariableOption;
import microbat.recommendation.UserFeedback;
import microbat.util.TraceUtil;
import microbat.views.MicroBatViews;
import microbat.views.TraceView;

public class StepwisePropagationHandler extends AbstractHandler {

	protected TraceView buggyView = null;
	private Stack<NodeFeedbacksPair> userFeedbackRecords = new Stack<>();
	
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Job job = new Job("Run Baseline") {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				return stepwisePropagation();
			}
			
		};
		job.schedule();
		return null;
	}
	
	protected IStatus stepwisePropagation() {
		// Get the trace view
		this.setup();
		
		System.out.println();
		System.out.println("---------------------------------------------");
		System.out.println("\t Stepwise Probability Propagation");
		System.out.println();
		
		// Check is the trace ready
		if (this.buggyView.getTrace() == null) {
			System.out.println("Please setup the trace before propagation");
			return Status.OK_STATUS;
		}
		
		// Check is the IO ready
		if (!this.isIOReady()) {
			System.out.println("Please provide the inputs and the outputs");
			return Status.OK_STATUS;
		}
		
		// Obtain the inputs and outputs from users
		// We only consider the first output
		final List<VarValue> inputs = DebugInfo.getInputs();
		final List<VarValue> outputs = DebugInfo.getOutputs();

		VarValue output = outputs.get(0);		
		TraceNode outputNode = null;
		if (output.getVarID().startsWith("CR_")) {
			// Initial feedback is wrong path
			NodeFeedbacksPair initPair = DebugInfo.getNodeFeedbackPair();
			outputNode = initPair.getNode();
		} else {
			outputNode = this.getStartingNode(buggyView.getTrace(), outputs.get(0));
		}
		
		
		// Set up the propagator that perform propagation,
		// with initial feedback indicating the output variable  is wrong
		SPP spp = new SPP(buggyView.getTrace(), inputs, outputs, outputNode);
		
		TraceNode currentNode = outputNode;
		
		boolean isEnd = false;
		// Keep doing propagation until the root cause is found
		while(!DebugInfo.isRootCauseFound() && !DebugInfo.isStop() && !isEnd) {
			// Perform propagation
			spp.updateFeedbacks(userFeedbackRecords);
			SPP.printMsg("Propagating probability ...");
			spp.propagate();
			SPP.printMsg("Locating root cause ...");
			spp.locateRootCause();
			SPP.printMsg("Constructing path to root cause ...");
			spp.constructPath();
			
			boolean needPropagateAgain = false;
			while (!needPropagateAgain && !isEnd) {
				UserFeedback predictedFeedback = spp.giveFeedback(currentNode);
				SPP.printMsg("--------------------------------------");
				SPP.printMsg("Predicted feedback of node: " + currentNode.getOrder() + ": " + predictedFeedback.toString());
				NodeFeedbacksPair userFeedbacks = this.askForFeedback(currentNode);
				if (userFeedbacks.containsFeedback(predictedFeedback)) {
					// Feedback predicted correctly, save the feedback into record and move to next node
					this.userFeedbackRecords.add(userFeedbacks);
					currentNode = TraceUtil.findNextNode(currentNode, predictedFeedback, this.buggyView.getTrace());
				} else if (userFeedbacks.getFeedbackType().equals(UserFeedback.CORRECT)) {
					/*	If the feedback is CORRECT, there are two reasons:
					 *  1. User give wrong feedback
					 *  2. Omission bug occur
					 *  
					 *  We first assume that user give a inaccurate feedback last iteration
					 *  and ask user to correct it. Since user may give multiple inaccurate
					 *  feedbacks, so that we will keep asking until the last accurate feedback
					 *  is located or we end up at the initial step.
					 *  
					 *  If user insist the previous feedback is accurate, then we say there is 
					 *  omission bug
					 */
					SPP.printMsg("You give CORRECT feedback at node: " + currentNode.getOrder());
					NodeFeedbacksPair prevRecord = this.userFeedbackRecords.peek();
					TraceNode prevNode = prevRecord.getNode();
					SPP.printMsg("Please confirm the feedback at previous node.");
					NodeFeedbacksPair correctingFeedbacks = this.askForFeedback(prevNode);
					if (correctingFeedbacks.equals(prevRecord)) {
						// Omission bug confirmed
						this.reportOmissionBug(currentNode, correctingFeedbacks);
						isEnd = true;
					}  else {
						boolean lastAccurateFeedbackLocated = false;
						this.userFeedbackRecords.pop();
						while (!lastAccurateFeedbackLocated && !isEnd) {
							prevRecord = this.userFeedbackRecords.peek();
							prevNode = prevRecord.getNode();
							SPP.printMsg("Please confirm the feedback at previous node.");
							correctingFeedbacks = this.askForFeedback(prevNode);
							if (correctingFeedbacks.equals(prevRecord)) {
								lastAccurateFeedbackLocated = true;
								currentNode = TraceUtil.findNextNode(prevNode, correctingFeedbacks.getFeedbacks().get(0), this.buggyView.getTrace());
								SPP.printMsg("Last accurate feedback located. Please start giveing feedback from node: " + currentNode.getOrder());
								continue;
							}
							this.userFeedbackRecords.pop();
							if (this.userFeedbackRecords.isEmpty()) {
								// Reach initial feedback
								SPP.printMsg("You are going to reach the initialize feedback which assumed to be accurate");
								SPP.printMsg("Pleas start giving from node: "+prevNode.getOrder());
								SPP.printMsg("If the initial feedback is inaccurate, please start the whole process again");
								currentNode = prevNode;
								lastAccurateFeedbackLocated = true;
							}
						}
					}
				} else if (TraceUtil.findNextNode(currentNode, userFeedbacks.getFirstFeedback(), this.buggyView.getTrace()) == null) {
					/* Next node is null. Possible reasons:
					 * 1. Wrong feedback is given
					 * 2. Omission bug occur
					 * 
					 * First assume a wrong feedback is given and ask user
					 * to correct it. After correction, if the feedback
					 * match with predicted feedback, then continue the process
					 * as if nothing happen. If the feedback mismatch, then
					 * handle it the same as wrong prediction.
					 * 
					 * If the user insist the feedback is accurate, then
					 * omission bug confirm
					 */
					SPP.printMsg("Cannot find next node. Please double check you feedback at node: " + currentNode.getOrder());
					NodeFeedbacksPair correctingFeedbacks = this.askForFeedback(currentNode);
					if (correctingFeedbacks.equals(userFeedbacks)) {
						// Omission bug confirmed
						final TraceNode startNode = currentNode.getInvocationParent() == null ? buggyView.getTrace().getTraceNode(1) : currentNode.getInvocationParent();
						this.reportOmissionBug(startNode, correctingFeedbacks);
						isEnd = true;
					} else {
						SPP.printMsg("Wong prediction on feedback, start propagation again");
						needPropagateAgain = true;
						this.userFeedbackRecords.add(correctingFeedbacks);
						currentNode = TraceUtil.findNextNode(currentNode, correctingFeedbacks.getFirstFeedback(), this.buggyView.getTrace());
					}
				} else {
					/*	Wrong prediction on feedback
					 *  We need to record it and start the propagation again
					 */
					SPP.printMsg("Wong prediction on feedback, start propagation again");
					needPropagateAgain = true;
					this.userFeedbackRecords.add(userFeedbacks);
					currentNode = TraceUtil.findNextNode(currentNode, userFeedbacks.getFirstFeedback(), this.buggyView.getTrace());
				}
			}
//			// Root cause prediction
//			TraceNode rootCause = spp.proposeRootCause();
//			System.out.println("Proposed Root Cause: " + rootCause.getOrder());
//			
//			System.out.println("Path finding ...");
//			ActionPath userPath = new ActionPath(userFeedbackRecords);
//			final ActionPath path = spp.suggestPath(currentNode, rootCause, userPath);
//			
//			System.out.println();
//			for (NodeFeedbacksPair section : path) {
//				System.out.println("Debug: " + section);
//			}
//			System.out.println();
//			
//			// Ensure that user current location is on the path
//			if (!path.contains(currentNode)) {
//				throw new RuntimeException("Suggested path does not contain current node");
//			}
//			
//			for (int idx=0; idx<path.getLength(); idx++) {
//				final NodeFeedbacksPair action = path.get(idx);
//				
//				// Go to the current location
//				final TraceNode node = action.getNode();
//				if (!node.equals(currentNode)) {
//					continue;
//				}
//				
//				this.jumpToNode(currentNode);
//				System.out.println("------------------------------");
//				System.out.println("Predicted feedback: ");
//				System.out.println(action);
//				
//				// Obtain feedback from user
//				NodeFeedbacksPair userFeedbackPair = askForFeedback(currentNode);
//				final UserFeedback predictedFeedback = action.getFeedbacks().get(0);
//				
//				// Feedback predicted correctly
//				if (userFeedbackPair.containsFeedback(predictedFeedback)) {
//					currentNode = TraceUtil.findNextNode(currentNode, predictedFeedback, buggyView.getTrace());
//					this.userFeedbackRecords.add(userFeedbackPair);
//					continue;
//				}
//				
//				// Feedback is predicted wrongly
//						
//				/*
//				 *  If the feedback is CORRECT, there are two reasons:
//				 *  1. User give wrong feedback
//				 *  2. Omission bug occur
//				 */
//				if (userFeedbackPair.getFeedbackType().equals(UserFeedback.CORRECT)) {
//					// We first assume user give a wrong feedback
//					NodeFeedbacksPair prevPair = userFeedbackRecords.peek();
//					UserFeedback prevFeedback = prevPair.getFeedbacks().get(0);
//					TraceNode prevNode = prevPair.getNode();
//					jumpToNode(prevNode);
//					System.out.println("[SPP] Please confirm again the feedback of this node: " + node.getOrder());
//					NodeFeedbacksPair correctingFeedbackPair = askForFeedback(node);
//					if (prevPair.equals(correctingFeedbackPair)) {
//						// User insist feedback is correct, omission bug confirmed
//						if (prevFeedback.getFeedbackType().equals(UserFeedback.WRONG_VARIABLE_VALUE)) {
//							final VarValue var = prevFeedback.getOption().getReadVar();
//							reportMissingAssignmentOmissionBug(node, prevNode, var);
//						} else {
//							reportMissingBranchOmissionBug(node, prevNode);
//						}
//						isEnd = true;
//					} else {
//						// User confirm that previous feedback is inaccurate, it is possible that they
//						// give more than one inaccurate feedback, so that we loop to find out the last accurate feedback
//						userFeedbackRecords.pop();
//						while (!userFeedbackRecords.isEmpty()) {
//							prevPair = userFeedbackRecords.peek();
//							prevNode = prevPair.getNode();
//							prevFeedback = prevPair.getFeedbacks().get(0);
//							jumpToNode(prevNode);
//							System.out.println("[SPP] Please confirm again the feedback of this node: " + node.getOrder());
//							correctingFeedbackPair = askForFeedback(node);
//							if (correctingFeedbackPair.equals(prevPair)) {
//								// Last accurate feedback located
//								break;
//							}
//							userFeedbackRecords.pop();
//						}
//						currentNode = TraceUtil.findNextNode(prevNode, prevFeedback, this.buggyView.getTrace());
//					}
//					break;
//				}
//				
//				UserFeedback userFeedback = userFeedbackPair.getFeedbacks().get(0);
//				TraceNode nextNode = TraceUtil.findNextNode(currentNode, userFeedback, buggyView.getTrace());
//				
//				/*
//				 * If the feedback is wrong path and there are no control dominator, 
//				 * there are several reasons:
//				 * 1. User give a wrong feedback
//				 * 2. Omission bug msing branch
//				 */
//				if (nextNode == null && userFeedback.getFeedbackType().equals(UserFeedback.WRONG_PATH)) {
//					// First assume user give a wrong feedback
//					System.out.println("[SPP] There are no control dominator of this step. Can you confirm again the feedback of node: " + node.getOrder());
//					final NodeFeedbacksPair correctingFeedbackPair = askForFeedback(node);
//					if (correctingFeedbackPair.equals(userFeedbackPair)) {
//						// User insist feedback is correct, omission bug confirmed
//						TraceNode beginNode = node.getInvocationParent();
//						if (beginNode == null) {
//							beginNode = buggyView.getTrace().getTraceNode(1);
//						}
//						reportMissingBranchOmissionBug(beginNode, node);
//						isEnd = true;
//						break;
//					} else {
//						// Check is the feedback match with the predicted feedback
//						if (correctingFeedbackPair.containsFeedback(predictedFeedback)) {
//							currentNode = TraceUtil.findNextNode(currentNode, predictedFeedback, buggyView.getTrace());
//							continue;
//						} else {
//							// if not, then process it the same way as wrong prediction
//							userFeedbackPair = correctingFeedbackPair;
//							userFeedback = userFeedbackPair.getFeedbacks().get(0);
//						}
//					}
//				}
//				
//				// Handle wrong prediction
//				this.userFeedbackRecords.add(userFeedbackPair);
//				currentNode = TraceUtil.findNextNode(currentNode, userFeedback, this.buggyView.getTrace());
//				break;
//			}
		}
		return Status.OK_STATUS;
	}
	
	
	protected void setup() {
		Display.getDefault().syncExec(new Runnable() {
			@Override
			public void run() {
				buggyView = MicroBatViews.getTraceView();
			}
		});
	}
	
	protected boolean isIOReady() {
		return !DebugInfo.getInputs().isEmpty() && !(DebugInfo.getOutputs().isEmpty());
	}
	
	protected void jumpToNode(final TraceNode targetNode) {
		Display.getDefault().asyncExec(new Runnable() {
		    @Override
		    public void run() {
				Trace buggyTrace = buggyView.getTrace();
				buggyView.jumpToNode(buggyTrace, targetNode.getOrder(), true);
		    }
		});
	}

	protected void printReport(final int noOfFeedbacks) {
		System.out.println("---------------------------------");
		System.out.println("Number of feedbacks: " + noOfFeedbacks);
		System.out.println("---------------------------------");
	}
	
	protected TraceNode getStartingNode(final Trace trace, final VarValue output) {
		for (int order = trace.size(); order>=0; order--) {
			TraceNode node = trace.getTraceNode(order);
			final String varID = output.getVarID();
			if (node.isReadVariablesContains(varID)) {
				return node;
			} else if (node.isWrittenVariablesContains(varID)) {
				return node;
			}
		}
		return null;
	}
	
	protected NodeFeedbacksPair askForFeedback(final TraceNode node) {
		this.jumpToNode(node);
		SPP.printMsg("Please give an feedback for node: " + node.getOrder());
		DebugInfo.waitForFeedbackOrRootCauseOrStop();
		NodeFeedbacksPair userPairs = DebugInfo.getNodeFeedbackPair();
		DebugInfo.clearNodeFeedbackPairs();
		System.out.println();
		SPP.printMsg("UserFeedback: " + userPairs);
		return userPairs;
	}
	
	protected void reportOmissionBug(final TraceNode startNode, final NodeFeedbacksPair feedback) {
		if (feedback.getFeedbackType().equals(UserFeedback.WRONG_PATH)) {
			this.reportMissingBranchOmissionBug(startNode, feedback.getNode());
		} else if (feedback.getFeedbackType().equals(UserFeedback.WRONG_VARIABLE_VALUE)) {
			VarValue varValue = feedback.getFeedbacks().get(0).getOption().getReadVar();
			this.reportMissingAssignmentOmissionBug(startNode, feedback.getNode(), varValue);
		}
	}
	protected void reportMissingBranchOmissionBug(final TraceNode startNode, final TraceNode endNode) {
		SPP.printMsg("-------------------------------------------");
		SPP.printMsg("Omission bug detected");
		SPP.printMsg("Scope begin: " + startNode.getOrder());
		SPP.printMsg("Scope end: " + endNode.getOrder());
		SPP.printMsg("Omission Type: Missing Branch");
		SPP.printMsg("-------------------------------------------");
	}
	
	protected void reportMissingAssignmentOmissionBug(final TraceNode startNode, final TraceNode endNode, final VarValue var) {
		SPP.printMsg("-------------------------------------------");
		SPP.printMsg("Omission bug detected");
		SPP.printMsg("Scope begin: " + startNode.getOrder());
		SPP.printMsg("Scope end: " + endNode.getOrder());
		SPP.printMsg("Omission Type: Missing Assignment of " + var.getVarName());
		SPP.printMsg("-------------------------------------------");
	}
}
