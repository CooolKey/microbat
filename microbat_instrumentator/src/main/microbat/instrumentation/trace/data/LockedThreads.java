package microbat.instrumentation.trace.data;

/**
 * 
 * @author lyly
 * simple version to keep lookedThread ids to avoid using java.util.ArrayList
 */
public class LockedThreads {
	int capacity = 100;
	long[] lockedThreadIds = new long[capacity];
	
	public LockedThreads() {
		for (int i = 0; i < capacity; i++) {
			lockedThreadIds[i] = -1l;
		}
	}

	public boolean contains(long threadId) {
		for (int i = 0; i < capacity; i++) {
			if (lockedThreadIds[i] == threadId) {
				return true;
			}
		}
		return false;
	}

	public void add(long threadId) {
		for (int i = 0; i < capacity; i++) {
			if (lockedThreadIds[i] < 0) {
				lockedThreadIds[i] = threadId;
				return;
			}
		}
		System.out.println("LockedThreads full!");
	}

	public void remove(long threadId) {
		for (int i = 0; i < capacity; i++) {
			if (lockedThreadIds[i] == threadId) {
				lockedThreadIds[i] = -1l;
				return;
			}
		}
	}
	
	
}
