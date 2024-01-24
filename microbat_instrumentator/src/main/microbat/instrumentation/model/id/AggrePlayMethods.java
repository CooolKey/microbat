package microbat.instrumentation.model.id;
/**
 * Events that are used in this instrumentation
 * @author Gabau
 *
 */
public enum AggrePlayMethods {
	ACQUIRE_LOCK("_acquireLock", "()V"),
	ON_LOCK_ACQUIRE("_onLock_Acquire", "(Ljava/lang/Object)V"),
	RELEASE_LOCK("_releaseLock", "()V"),
	ON_NEW_OBJECT("_onNewObject", "(Ljava/lang/Object;)V"),
	BEFORE_OBJECT_READ("_onObjectRead", "(Ljava/lang/Object;Ljava/lang/String;)V"),
	AFTER_OBJECT_READ("_afterObjectRead", "()V"),
	BEFORE_OBJECT_WRITE("_onObjectWrite", "(Ljava/lang/Object;Ljava/lang/String;)V");
	
	
	public final String methodName;
	public final String methodSig;
	
	private AggrePlayMethods(String methodName, String methodSig) {
		this.methodName = methodName;
		this.methodSig = methodSig;
	}
	
}
