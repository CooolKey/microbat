package microbat.instrumentation.instr.aggreplay.agents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import microbat.instrumentation.Agent;
import microbat.instrumentation.AgentLogger;
import microbat.instrumentation.AgentParams;
import microbat.instrumentation.CommandLine;
import microbat.instrumentation.TraceAgent;
import microbat.instrumentation.instr.SystemClassTransformer;
import microbat.instrumentation.instr.ThreadInstrumenter;
import microbat.instrumentation.instr.TraceInstrumenter;
import microbat.instrumentation.instr.TraceTransformer;
import microbat.instrumentation.instr.aggreplay.AggrePlayTraceInstrumenter;
import microbat.instrumentation.instr.aggreplay.ThreadIdInstrumenter;
import microbat.instrumentation.instr.aggreplay.shared.BasicTransformer;
import microbat.instrumentation.instr.aggreplay.shared.ParseData;
import microbat.instrumentation.instr.aggreplay.shared.RecordingOutput;
import microbat.instrumentation.instr.aggreplay.shared.SharedDataParser;
import microbat.instrumentation.model.ReadWriteAccessListReplay;
import microbat.instrumentation.model.generator.ObjectIdGenerator;
import microbat.instrumentation.model.generator.SharedMemoryGenerator;
import microbat.instrumentation.model.generator.ThreadIdGenerator;
import microbat.instrumentation.model.id.Event;
import microbat.instrumentation.model.id.ReadCountVector;
import microbat.instrumentation.model.id.ReadWriteAccessList;
import microbat.instrumentation.model.id.SharedMemoryLocation;
import microbat.instrumentation.model.id.ThreadId;
import microbat.instrumentation.runtime.ExecutionTracer;
import microbat.instrumentation.runtime.IExecutionTracer;
import microbat.model.trace.Trace;
import microbat.sql.Recorder;

public class AggrePlayReplayAgent extends TraceAgent {
	
	private AgentParams agentParams;
	private IExecutionTracer executionTracer;
	
	/**
	 * Current replay values
	 */
	private ObjectIdGenerator objectIdGenerator = new ObjectIdGenerator();
	private SharedMemoryGenerator sharedMemGenerator = new SharedMemoryGenerator(objectIdGenerator);
	private ClassFileTransformer transformer;
	private static AggrePlayReplayAgent attachedAgent;
	private ReadCountVector rcVector = new ReadCountVector();
	// null if the last event not was shared
	ThreadLocal<Event> lastEventLocal = ThreadLocal.withInitial(new Supplier<Event>() {
		@Override
		public Event get() {
			return null;
		}
	});
	// maps from recording thread to replay
	private HashMap<Long, Long> threadIdMap = new HashMap<>();
	private ThreadIdGenerator threadIdGenerator = new ThreadIdGenerator();
	
	/**
	 * Recorded output
	 */
	private ReadWriteAccessList rwal;
	private RecordingOutput recordingOutput;
	private HashMap<ThreadId, Long> recordedThreadIdMap = new HashMap<>();
	private ReadWriteAccessListReplay rwalGeneratedAccessListReplay;
	
	public static void _onNewObject(Object object) {
		attachedAgent.onObjectCreate(object);
	}
	
	private void onObjectCreate(Object object) {
		objectIdGenerator.createId(object);
	}
	
	public static void _onThreadStart(Thread thread) {
		attachedAgent.onThreadStart(thread);
	}
	
	private void onThreadStart(Thread thread) {
		threadIdGenerator.createId(thread);
		ThreadId tId = threadIdGenerator.getId(thread);
		threadIdMap.put(thread.getId(), recordedThreadIdMap.get(tId));
	}
	
	public AggrePlayReplayAgent(CommandLine cmd) {
		super(cmd);
		agentParams = AgentParams.initFrom(cmd);
		this.transformer = new BasicTransformer(new AggrePlayTraceInstrumenter(agentParams));
	}
	

	
	public static AggrePlayReplayAgent getAttached(CommandLine cmd) {
		if (attachedAgent == null) {
			attachedAgent = new AggrePlayReplayAgent(cmd);
		}
		attachedAgent.agentParams = AgentParams.initFrom(cmd);
		return attachedAgent;
	}
	
	/**
	 * Used to get the thread id from the previous thread which the current thread maps to.
	 * @return
	 */
	private long getPreviousThreadId() {
		Long previousThreadID = threadIdMap.get(Thread.currentThread().getId());
		if (previousThreadID == null) {
			// this is the root thread.
			return recordedThreadIdMap.get(threadIdGenerator.getRoot());
		}
		return previousThreadID;
	}
	
	private void onObjectRead(Object object, String field) {
		if (!attachedAgent.sharedMemGenerator.isSharedObject(object, field)) {
			lastEventLocal.set(null);
			return;
		}

		long previousThreadId = getPreviousThreadId();
		SharedMemoryLocation sharedMemLocation = attachedAgent.sharedMemGenerator.ofField(object, field);
		// TODO(Gab): all events are recorded using the previous thread id
		// consider a better alternative
		Event readEvent = new Event(sharedMemLocation, previousThreadId);
		lastEventLocal.set(readEvent);
		if (!sharedMemLocation.isSameAsLastWrite(readEvent)) {
			Thread.yield();
			lastEventLocal.set(null);
		}
	}
	public static void _onObjectRead(Object object, String field) {
		attachedAgent.onObjectRead(object, field);
	}
	
	public static void _onObjectWrite(Object object, String field) {
		attachedAgent.onObjectWrite(object, field);
	}
	
	private void onObjectWrite(Object object, String field) {
		if (!sharedMemGenerator.isSharedObject(object, field)) {
			lastEventLocal.set(null);
			return;
		}
		long p_tid = getPreviousThreadId();
		SharedMemoryLocation shm = sharedMemGenerator.ofField(object, field);
		Event writeEvent = new Event(shm, getPreviousThreadId());
		synchronized (rcVector) {
			rcVector.updateReadVectors(shm.getLocation(), p_tid);
		}
		
		if (!shm.isSameAsPrevRunWrite(writeEvent) || !checkReads(p_tid)) {
			Thread.yield();
			lastEventLocal.set(null);
			return;
		}
		lastEventLocal.set(writeEvent);
		
	}
	
	public static void _afterObjectWrite() {
		attachedAgent.afterObjectWrite();
	}
	
	public boolean checkReads(long threadId) {
		return rwalGeneratedAccessListReplay.checkRead(rcVector, threadId);
	}
	
	private void afterObjectWrite() {
		if (lastEventLocal.get() == null) {
			return;
		}
		SharedMemoryLocation mlcation = lastEventLocal.get().getLocation();
		mlcation.addRepWriteEvent(lastEventLocal.get());
		mlcation.popEvent();
		
	}
	public static void _afterObjectRead() {
		attachedAgent.afterObjectRead();
	}
	
	private void afterObjectRead() {
		if (lastEventLocal.get() == null) return;
		long t = getPreviousThreadId();
		SharedMemoryLocation mLocation = lastEventLocal.get().getLocation();
		// TODO(Gab): can this run in parallel?
		synchronized (mLocation) {
			rcVector.increment(t, lastEventLocal.get().getLocation().getLocation());	
			mLocation.popRecordedLastWR();
		}
	}
	
	@Override
	public void startup0(long vmStartupTime, long agentPreStartup) {
		SystemClassTransformer.attachThreadId(getInstrumentation(), this.getClass());
		File concDumpFile = new File(agentParams.getConcDumpFile());
		try {
			FileReader concReader = new FileReader(concDumpFile);
			RecordingOutput input = new RecordingOutput();
			SharedDataParser parser = new SharedDataParser();
			List<ParseData> result = parser.parse(concReader);
			RecordingOutput output = input.parse(result.get(0));
			this.rwal = output.rwAccessList;
			this.recordingOutput = output;
			this.rwalGeneratedAccessListReplay = new ReadWriteAccessListReplay(rwal);
			for (ThreadId threadId: recordingOutput.threadIds) {
				this.recordedThreadIdMap.put(threadId, threadId.getId());
			}
			this.sharedMemGenerator.init(this.recordingOutput);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.exit(-1);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.exit(-1);
		}
		super.startup0(vmStartupTime, agentPreStartup);
		ExecutionTracer._start();
	}
	

	@Override
	public void shutdown() throws Exception {
		// Agent._exitProgram(getProgramMsg());
		System.out.println("Shutting down");
		try {

			shutdownInner();
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("Shutdown");
	}

	private void shutdownInner() {
		ExecutionTracer.shutdown();
		/* collect trace & store */
		AgentLogger.debug("Building trace dependencies ...");
//		timer.newPoint("Building trace dependencies");
		// FIXME -mutithread LINYUN [3]
		// LLT: only trace of main thread is recorded.
		List<IExecutionTracer> tracers = ExecutionTracer.getAllThreadStore();

		int size = tracers.size();
		List<Trace> traceList = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {

			ExecutionTracer tracer = (ExecutionTracer) tracers.get(i);

			Trace trace = tracer.getTrace();
			trace.setThreadId(tracer.getThreadId());
			trace.setThreadName(tracer.getThreadName());
			trace.setMain(ExecutionTracer.getMainThreadStore().equals(tracer));
			
			// TODO(Gab): Botch needed because tracer can be initialised 
			// in thread id instrumenter
			if (trace.getAppJavaClassPath() == null) {
				trace.setAppJavaClassPath(ExecutionTracer.appJavaClassPath);
			}
			constructTrace(trace);
			traceList.add(trace);
		}
		
//		timer.newPoint("Saving trace");
		Recorder.create(agentParams).store(traceList);
//		AgentLogger.debug(timer.getResultString());
	}
	

	@Override
	public void finishTest(String junitClass, String junitMethod) {
		// TODO Auto-generated method stub
		
	}
	
	


	@Override
	public ClassFileTransformer getTransformer() {
		// TODO Auto-generated method stub
		return this.transformer;
	}

	@Override
	public void retransformBootstrapClasses(Instrumentation instrumentation, Class<?>[] retransformableClasses)
			throws Exception {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void exitTest(String testResultMsg, String junitClass, String junitMethod, long threadId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean isInstrumentationActive0() {
		// TODO Auto-generated method stub
		return false;
	}

}
