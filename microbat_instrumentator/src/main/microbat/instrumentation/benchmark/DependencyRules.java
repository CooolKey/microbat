package microbat.instrumentation.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.ClassGen;
import org.apache.commons.io.IOUtils;

public class DependencyRules {
	
	static public Set<String> classes = new HashSet<>();
	static public Map<String, Set<String>> writters = new HashMap<>();
	static public Map<String, Set<String>> getters = new HashMap<>();
	
	static public enum Type {
		NONE,
		IS_WRITTER,
		IS_GETTER
	}

	public DependencyRules() {}
	
	public static void setUp() {
		String[] classNames = new String[] {
				"java.util.List",
				"java.util.Map",
				"java.util.Set",
				"java.util.Collection",
				"java.lang.StringBuffer",
				"java.io.Writer",
				"java.io.StringWriter",
				"java.io.PrintWriter"
				};
		
		List<List<String>> writterMethods = Arrays.asList(
				// list
				Arrays.asList("add(Ljava/lang/Object;)Z",
						"add(ILjava/lang/Object;)V",
						"addAll(Ljava/util/Collection;)Z",
						"addAll(ILjava/util/Collection;)Z",
						"clear()V",
						"remove(I)Ljava/lang/Object;",
						"remove(Ljava/lang/Object;)Z",
						"removeAll(Ljava/util/Collection;)Z",
						"replaceAll(Ljava/util/function/UnaryOperator;)V",
						"retainAll(Ljava/util/Collection;)Z",
						"set(ILjava/lang/Object;)Ljava/lang/Object;",
						"sort(Ljava/util/Comparator;)V;"),
				// map
				Arrays.asList("clear()Z",
						"forEach(Ljava/util/function/BiConsumer;)Z",
						"merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
						"put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
						"putAll(Ljava/util/Map;)Z",
						"putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
						"remove(Ljava/lang/Object;)Ljava/lang/Object;",
						"remove(Ljava/lang/Object;Ljava/lang/Object;)Z",
						"replace(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
						"replace(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
						"replaceAll(Ljava/util/function/BiFunction;)V"),
				// set
				Arrays.asList("add(Ljava/lang/Object;)Z",
						"addAll(Ljava/util/Collection;)Z",
						"clear()V",
						"remove(Ljava/lang/Object;)Z",
						"removeAll(Ljava/util/Collection;)Z",
						"retainAll(Ljava/util/Collection;)Z"),
				// collection
				Arrays.asList("add(Ljava/lang/Object;)Z",
						"addAll(Ljava/util/Collection;)Z",
						"clear()V",
						"remove(Ljava/lang/Object;)Z",
						"removeAll(Ljava/util/Collection;)Z",
						"removeIf(Ljava/util/function/Predicate;)Z",
						"retainAll(Ljava/util/Collection;)Z"),
				// stringbuffer
				Arrays.asList("append(Z)Ljava/lang/StringBuffer;",
						"append(C)Ljava/lang/StringBuffer;",
						"append([C)Ljava/lang/StringBuffer;",
						"append([CII)Ljava/lang/StringBuffer;",
						"append(Ljava/lang/CharSequence;)Ljava/lang/StringBuffer;",
						"append(Ljava/lang/CharSequence;II)Ljava/lang/StringBuffer;",
						"append(D)Ljava/lang/StringBuffer;",
						"append(F)Ljava/lang/StringBuffer;",
						"append(I)Ljava/lang/StringBuffer;",
						"append(J)Ljava/lang/StringBuffer;",
						"append(Ljava/lang/Object;)Ljava/lang/StringBuffer;",
						"append(Ljava/lang/String;)Ljava/lang/StringBuffer;",
						"append(Ljava/lang/StringBuffer;)Ljava/lang/StringBuffer;",
						"appendCodePoint(I)Ljava/lang/StringBuffer;",
						"delete(II)Ljava/lang/StringBuffer;",
						"deleteCharAt(I)Ljava/lang/StringBuffer;",
						"ensureCapacity(I)V;",
						"insert(IZ)V",
						"insert(IC)V",
						"insert(I[C)V",
						"insert(I[CII)V",
						"insert(ILjava/lang/CharSequence;)V",
						"insert(ILjava/lang/CharSequence;II)V",
						"insert(ID)V",
						"insert(IF)V",
						"insert(II)V",
						"insert(IJ)V",
						"insert(ILjava/lang/Object;)V",
						"insert(ILjava/lang/String;)V",
						"replace(IILjava/lang/String;)Ljava/lang/StringBuffer;",
						"reverse()Ljava/lang/StringBuffer;",
						"setCharAt(IC)V;",
						"setLength(I)V;",
						"trimToSize()V;"),
				// writer
				Arrays.asList("write([C)V",
						"write([CII)V",
						"write(I)V",
						"write(Ljava/lang/String;)V",
						"write(Ljava/lang/String;II)V",
						"append(C)Ljava/io/Writer;",
						"append(Ljava/lang/CharSequence;)Ljava/io/Writer;",
						"append(Ljava/lang/CharSequence;II)Ljava/io/Writer;",
						"close()V;",
						"flush()V;"),
				// stringwriter
				Arrays.asList("append(C)Ljava/io/StringWriter;",
						"append(Ljava/lang/CharSequence;)Ljava/io/StringWriter;",
						"append(Ljava/lang/CharSequence;II)Ljava/io/StringWriter;",
						"close()V;",
						"flush()V;"),
				// printwriter
				Arrays.asList("append(C)Ljava/io/PrintWriter;",
						"append(Ljava/lang/CharSequence;)Ljava/io/PrintWriter;",
						"append(Ljava/lang/CharSequence;II)Ljava/io/PrintWriter;",
						"checkError()Z;",
						"clearError()V;",
						"close()V;",
						"flush()V;",
						"format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintWriter;",
						"format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintWriter;",
						"setError()V;",
						"write([C)V;",
						"write([CII)V;",
						"write(I)V;",
						"write(Ljava/lang/String;)V;",
						"write(Ljava/lang/String;II)V;")
				);
		
//		List<List<String>> getterMethods = Arrays.asList(
//				// list
//				Arrays.asList("get(I)Ljava/lang/Object;"),
//				// map
//				Arrays.asList("get(Ljava/lang/Object;)Ljava/lang/Object;"),
//				// set
//				Arrays.asList(""),
//				// collection
//				Arrays.asList(""),
//				// appendable
//				Arrays.asList(""),
//				// charsequence
//				Arrays.asList("charAt(I)C", 
//						"subSequence(II)Ljava/lang/CharSequence;", 
//						"toString()Ljava/lang/String;")
//				);
		
		for (int i = 0; i < classNames.length; i++) {
			String key = classNames[i];
			classes.add(key);
			writters.put(key, new HashSet<String>(writterMethods.get(i)));
//			getters.put(key, new HashSet<String>(getterMethods.get(i)));
		}
	}
	
	public static Type getType(String method) throws IOException {
		if (classes.isEmpty()) {
			setUp();
		}
		
		String[] methodInfo = method.split("#");
		String className = methodInfo[0];
		String methodSignature = methodInfo[1];
		
		/*
		 * load the class
		 */
		ClassGen classGen = loadClass(className);
		if (classGen == null) {
			return Type.NONE;
		}
		
		/*
		 * get all relevant classes
		 */
		ArrayList<String> relevantClasses = new ArrayList<>();
		relevantClasses.add(className);
		for (String interfaceName : classGen.getInterfaceNames()) {
			relevantClasses.add(interfaceName);
		}
		relevantClasses.add(classGen.getSuperclassName());
		
		for (String clazz : relevantClasses) {
			if (classes.contains(clazz)) {
				if (writters.get(clazz).contains(methodSignature)) {
					return Type.IS_WRITTER;
				}
//				if (getters.get(clazz).contains(methodSignature)) {
//					return Type.IS_GETTER;
//				}
			}
		}
		return Type.NONE;
	}
	
	private static String getFileName(String classFName) {
		return classFName + ".class";
	}
	
	private static ClassGen loadClass(String className) throws IOException {
		String classFName = className.replace('.', '/');
		String fileName = getFileName(classFName);
		byte[] bytecode = loadByteCode(fileName);
		
		ClassParser cp = new ClassParser(new java.io.ByteArrayInputStream(bytecode), classFName);
		JavaClass jc = cp.parse();
		return new ClassGen(jc);
	}
	
	private static byte[] loadByteCode(String fileName) throws IOException {
		InputStream inputStream = ClassLoader
        		.getSystemClassLoader()
        		.getResourceAsStream(fileName);
		if (inputStream != null) {
			byte[] bytecode = IOUtils.toByteArray(inputStream);
            inputStream.close();
            return bytecode;
        }
		return null;
	}

}