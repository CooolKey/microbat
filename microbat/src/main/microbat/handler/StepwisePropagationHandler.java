package microbat.handler;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import debuginfo.DebugInfo;
import debuginfo.NodeFeedbackPair;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;
import microbat.probability.SPP.SPP;
import microbat.probability.SPP.pathfinding.ActionPath;
import microbat.recommendation.UserFeedback;
import microbat.util.TraceUtil;
import microbat.views.MicroBatViews;
import microbat.views.TraceView;

public class StepwisePropagationHandler extends AbstractHandler {

	protected TraceView buggyView = null;
	
	private ActionPath userPath = new ActionPath();

	
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Job job = new Job("Run Baseline") {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				
				// Get the trace view
				setup();
				
				System.out.println();
				System.out.println("---------------------------------------------");
				System.out.println("\t Stepwise Probability Propagation");
				System.out.println();
				
				// Check is the trace ready
				if (buggyView.getTrace() == null) {
					System.out.println("Please setup the trace before propagation");
					return Status.OK_STATUS;
				}
				
				// Check is the IO ready
				if (!isIOReady()) {
					System.out.println("Please provide the inputs and the outputs");
					return Status.OK_STATUS;
				}
				
				// Obtain the inputs and outputs from users
				List<VarValue> inputs = DebugInfo.getInputs();
				List<VarValue> outputs = DebugInfo.getOutputs();
				
				final TraceNode startingNode = getStartingNode(buggyView.getTrace(), outputs.get(0));

			
				// Set up the propagator that perform propagation
				SPP spp = new SPP(buggyView.getTrace(), inputs, outputs);

				int feedbackCounts = 0;
				TraceNode currentNode = startingNode;
				boolean isOmissionBug = false;
				
				// Keep doing propagation until the root cause is found
				while(!DebugInfo.isRootCauseFound() && !DebugInfo.isStop()) {
					System.out.println("---------------------------------- " + feedbackCounts + " iteration");
					System.out.println("Finding path to root cause ...");
					
					spp.propagate();
					
					// Get the predicted root cause
					TraceNode rootCause = spp.proposeRootCause();
					System.out.println("Proposed Root Cause: " + rootCause.getOrder());
					
					// Visualization
					jumpToNode(rootCause);
					
					// Handle the case that root cause is at the downstream of current node
					if (rootCause.getOrder() > currentNode.getOrder()) {
						System.out.println();
						System.out.println("Proposed a wrong root cause becuase it is the downstream of current node: " + currentNode.getOrder());
						System.out.println("Give feedback based on probability:");
						UserFeedback predictedFeedback = spp.giveFeedback(currentNode);
						System.out.println(predictedFeedback);
						NodeFeedbackPair userPair = askForFeedback(currentNode);
						UserFeedback userFeedback = userPair.getFeedback();
						currentNode = TraceUtil.findNextNode(currentNode, userFeedback, buggyView.getTrace());
						continue;
					}
					
					final ActionPath path = spp.suggestPath(currentNode, rootCause, userPath);
					System.out.println();
					System.out.println("Debug: Suggested Pathway");
					for (NodeFeedbackPair section : path) {
						System.out.println("Debug: " + section);
					}
					System.out.println();
					
					assert path.contains(currentNode) : "Suggested path does not contain the current node: " + currentNode.getOrder();
					
					List<NodeFeedbackPair> responses = new ArrayList<>();
					for (NodeFeedbackPair action : path) {
						
						final TraceNode node = action.getNode();
						if (!node.equals(currentNode)) {
							continue;
						}
						
						jumpToNode(currentNode);
						
						System.out.println("Predicted feedback: ");
						System.out.println(action);
						
						// Obtain feedback from user
						NodeFeedbackPair userPair = askForFeedback(currentNode);
						userPath.addPair(userPair);
						responses.add(userPair);
						
						System.out.println("User Feedback: ");
						System.out.println(userPair);
						
						UserFeedback userFeedback = userPair.getFeedback();
						UserFeedback predictedFeedback = action.getFeedback();

						if (userFeedback.week_equals(predictedFeedback)) {
							// Predict correctly
							currentNode = TraceUtil.findNextNode(currentNode, predictedFeedback, buggyView.getTrace());
						} else {
							if (userFeedback.getFeedbackType().equals(UserFeedback.CORRECT)) {
								isOmissionBug = true;
							} else {
								spp.responseToFeedbacks(responses);
								currentNode = TraceUtil.findNextNode(currentNode, userFeedback, buggyView.getTrace());
								
								// Check is it control flow omission bug
								if (currentNode == null && userFeedback.getFeedbackType().equals(UserFeedback.WRONG_PATH)) {
									isOmissionBug = true;
								}
							}
							break;
						}
					}
					
					if (isOmissionBug) {
						System.out.println("Omission bug detected");
						break;
					}
				}
				
				return Status.OK_STATUS;
			}
			
		};
		job.schedule();
		return null;
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
		return !DebugInfo.getInputs().isEmpty() && !DebugInfo.getOutputs().isEmpty();
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
	
	protected NodeFeedbackPair askForFeedback(final TraceNode node) {
		System.out.println("Please give an feedback for node: " + node.getOrder());
		DebugInfo.waitForFeedbackOrRootCauseOrStop();
		NodeFeedbackPair userPair = DebugInfo.getNodeFeedbackPair();
		System.out.println();
		System.out.println("UserFeedback:");
		System.out.println(userPair);
		return userPair;
	}
}