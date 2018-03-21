package microbat.mutation.trace;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;

import microbat.mutation.trace.dto.AnalysisParams;
import microbat.mutation.trace.dto.AnalysisTestcaseParams;
import microbat.mutation.trace.dto.MutationCase;
import microbat.mutation.trace.dto.SingleMutation;
import microbat.mutation.trace.dto.TraceExecutionInfo;
import tregression.empiricalstudy.EmpiricalTrial;
import tregression.empiricalstudy.TrialRecorder;
import tregression.io.ExcelReporter;
import tregression.model.Trial;

public class MutationExperimentMonitor implements IMutationExperimentMonitor {
	private IProgressMonitor progressMonitor;
	private ExcelReporter reporter;

	public MutationExperimentMonitor(IProgressMonitor progressMonitor, String targetProject,
			AnalysisParams analysisParams) throws IOException {
		this.progressMonitor = progressMonitor;
		reporter = new ExcelReporter(targetProject, analysisParams.getUnclearRates());
	}

	@Override
	public boolean isCanceled() {
		return progressMonitor.isCanceled();
	}

	@Override
	public void reportTrial(AnalysisTestcaseParams params, TraceExecutionInfo correctTrace,
			TraceExecutionInfo killingMutatantTrace, SingleMutation mutation, boolean foundRootCause) {
		Trial trial = new Trial();
		trial.setTestCaseName(params.getTestcaseName());
		trial.setMutatedFile(mutation.getFile().toString());
		trial.setMutatedLineNumber(mutation.getLine());
		trial.setMutationType(mutation.getMutationType());
		trial.setOriginalTotalSteps(correctTrace.getTrace().getExecutionList().size());
		trial.setTotalSteps(killingMutatantTrace.getTrace().getExecutionList().size());
		trial.setMutatedFile(mutation.getFile().getAbsolutePath());
		trial.setBugFound(foundRootCause);
		reporter.export(Arrays.asList(trial));
		MutationCase mutationCase = new MutationCase(params, mutation);
		mutationCase.setBugTraceExec(killingMutatantTrace.getExecPath());
		mutationCase.setCorrectTraceExec(correctTrace.getExecPath());
		mutationCase.store();
	}

	@Override
	public void reportEmpiralTrial(List<EmpiricalTrial> trials0, AnalysisTestcaseParams params, SingleMutation mutation)
			throws IOException {
		TrialRecorder recorder = new TrialRecorder();
		recorder.export(trials0, params.getProjectName(), mutation.getMutationBugId(), mutation.getMutationType());

	}
}