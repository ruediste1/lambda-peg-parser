package com.github.ruediste1.lambdaPegParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.RemappingMethodAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

public class ParserFactory {

	public static class WeavedClassLoader extends ClassLoader {

		private String parserClassName;
		private byte[] weavedByteCode;

		public WeavedClassLoader(ClassLoader parent, String parserClassName,
				byte[] weavedByteCode) {
			super(parent);
			this.parserClassName = parserClassName;
			this.weavedByteCode = weavedByteCode;
		}

		@Override
		public Class<?> loadClass(String name) throws ClassNotFoundException {

			if (name.equals(parserClassName)) {
				defineClass(parserClassName, weavedByteCode, 0,
						weavedByteCode.length);
			}
			return super.loadClass(name);
		}
	}

	public static <T> T create(Class<? extends T> cls, Class<T> intrface,
			String input) {
		ParsingContext ctx = new ParsingContext(input);
		return create(cls, intrface, ctx);
	}

	@SuppressWarnings("unchecked")
	public static <T> T create(Class<? extends T> cls, Class<T> intrface,
			ParsingContext ctx) {
		return (T) instantiateWeavedParser(ctx, cls.getName());
	}

	public static <T extends Parser> T create(Class<T> cls, String input) {
		ParsingContext ctx = new ParsingContext(input);
		return create(cls, ctx);
	}

	@SuppressWarnings("unchecked")
	public static <T extends Parser> T create(Class<T> cls, ParsingContext ctx) {
		String parserClassNasme = cls.getName();
		Object weavedParser = instantiateWeavedParser(ctx, parserClassNasme);

		Enhancer e = new Enhancer();
		e.setSuperclass(cls);

		e.setCallback(new MethodInterceptor() {

			@Override
			public Object intercept(Object obj, Method method, Object[] args,
					MethodProxy proxy) throws Throwable {
				Method m = weavedParser.getClass().getDeclaredMethod(
						method.getName(), method.getParameterTypes());
				m.setAccessible(true);
				try {
					return m.invoke(weavedParser, args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			}
		});

		return (T) e.create(new Class[] { ParsingContext.class },
				new Object[] { ctx });

	}

	private static Object instantiateWeavedParser(ParsingContext ctx,
			String parserClassNasme) {

		Class<?> weavedClass;
		try {
			weavedClass = new WeavedClassLoader(
					ParserFactory.class.getClassLoader(), parserClassNasme,
					weaveClass(parserClassNasme)).loadClass(parserClassNasme);

			Constructor<?> constructor = weavedClass
					.getConstructor(ParsingContext.class);
			constructor.setAccessible(true);
			Object weavedParser = constructor.newInstance(ctx);
			return weavedParser;
		} catch (ClassNotFoundException | NoSuchMethodException
				| SecurityException | InstantiationException
				| IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			throw new RuntimeException(
					"Error while weaving and instantiating parser class", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static byte[] weaveClass(String parserClassName) {
		InputStream in = ParserFactory.class.getClassLoader()
				.getResourceAsStream(
						parserClassName.replace('.', '/') + ".class");
		ClassReader classReader;
		try {
			classReader = new ClassReader(in);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// load class
		ClassNode cn = new ClassNode();
		classReader.accept(cn, ClassReader.EXPAND_FRAMES);

		// load prototype method
		MethodNode prototype = loadPrototypeMethodNode();

		// modify methods
		for (int i = 0; i < cn.methods.size(); i++) {
			MethodNode ruleNode = (MethodNode) cn.methods.get(i);
			if ("<init>".equals(ruleNode.name))
				continue;
			if ((Opcodes.ACC_STATIC & ruleNode.access) != 0)
				continue;
			if ((Opcodes.ACC_SYNTHETIC & ruleNode.access) != 0)
				continue;

			// get minimum and maximum line numbers
			MinMaxLineMethodAdapter minMaxLineMethodAdapter = new MinMaxLineMethodAdapter(
					Opcodes.ASM5, null);
			ruleNode.accept(minMaxLineMethodAdapter);

			MethodNode newNode;
			{
				String[] exceptions = ((List<String>) ruleNode.exceptions)
						.toArray(new String[] {});

				newNode = new MethodNode(ruleNode.access, ruleNode.name,
						ruleNode.desc, ruleNode.signature, exceptions);
			}

			RemappingMethodAdapter remapper = new RemappingMethodAdapter(
					ruleNode.access, ruleNode.desc, newNode,
					new SimpleRemapper(PrototypeParser.class.getName().replace(
							'.', '/'), parserClassName.replace('.', '/')));

			MethodCallInliner inliner = new MethodCallInliner(remapper,
					ruleNode, minMaxLineMethodAdapter);

			PrototypeCustomizer prototypeCustomizer = new PrototypeCustomizer(
					inliner, ruleNode, i);

			prototype.accept(prototypeCustomizer);

			cn.methods.set(i, newNode);
		}

		// dump weaved byte code
		// cn.accept(new TraceClassVisitor(null, new Textifier(), new
		// PrintWriter( System.out)));

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS
				+ ClassWriter.COMPUTE_FRAMES);
		cn.accept(cw);

		byte[] b = cw.toByteArray();

		// verify weaved byte code
		PrintWriter pw = new PrintWriter(System.out);
		CheckClassAdapter.verify(new ClassReader(b), false, pw);

		return b;
	}

	private static MethodNode loadPrototypeMethodNode() {
		InputStream in = ParserFactory.class.getClassLoader()
				.getResourceAsStream(
						PrototypeParser.class.getName().replace('.', '/')
								+ ".class");
		ClassReader classReader;
		try {
			classReader = new ClassReader(in);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		ClassNode classNode = new ClassNode();
		classReader.accept(classNode, ClassReader.EXPAND_FRAMES);
		Iterator<?> it = classNode.methods.iterator();
		while (it.hasNext()) {
			MethodNode node = (MethodNode) it.next();
			if ("prototypeAdvice".equals(node.name)) {
				return node;
			}
		}
		throw new RuntimeException("Prototype method not found");
	}
}