/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.injector;

import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import net.runelite.api.mixins.Mixin;
import net.runelite.asm.*;
import net.runelite.asm.attributes.annotation.Annotation;
import net.runelite.asm.attributes.code.Instruction;
import net.runelite.asm.attributes.code.instruction.types.FieldInstruction;
import net.runelite.asm.attributes.code.instruction.types.InvokeInstruction;
import net.runelite.asm.attributes.code.instructions.*;
import net.runelite.asm.signature.Signature;
import net.runelite.asm.visitors.ClassFileVisitor;
import net.runelite.deob.DeobAnnotations;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class MixinInjector
{
	private static final Logger logger = LoggerFactory.getLogger(MixinInjector.class);

	private static final Type INJECT = new Type("Lnet/runelite/api/mixins/Inject;");
	private static final Type SHADOW = new Type("Lnet/runelite/api/mixins/Shadow;");
	private static final Type COPY = new Type("Lnet/runelite/api/mixins/Copy;");
	private static final Type REPLACE = new Type("Lnet/runelite/api/mixins/Replace;");

	private static final String MIXIN_BASE = "net.runelite.mixins";

	private final Inject inject;

	// field name -> Field of injected fields
	private final Map<String, Field> injectedFields = new HashMap<>();
	// Use net.runelite.asm.pool.Field instead of Field because the pool version has hashcode implemented
	private final Map<net.runelite.asm.pool.Field, Field> shadowFields = new HashMap<>();

	public MixinInjector(Inject inject)
	{
		this.inject = inject;
	}

	public void inject() throws InjectionException
	{
		ClassPath classPath;

		try
		{
			classPath = ClassPath.from(this.getClass().getClassLoader());
		}
		catch (IOException ex)
		{
			throw new InjectionException(ex);
		}

		// key: mixin class
		// value: mixin targets
		Map<Class<?>, List<ClassFile>> mixinClasses = new HashMap<>();

		// Find mixins and populate mixinClasses
		for (ClassInfo classInfo : classPath.getTopLevelClasses(MIXIN_BASE))
		{
			Class<?> mixinClass = classInfo.load();
			List<ClassFile> mixinTargets = new ArrayList<>();

			for (Mixin mixin : mixinClass.getAnnotationsByType(Mixin.class))
			{
				Class<?> implementInto = mixin.value();

				ClassFile targetCf = findVanillaForInterface(implementInto);

				if (targetCf == null)
				{
					throw new InjectionException("No class implements " + implementInto + " for mixin " + mixinClass);
				}

				mixinTargets.add(targetCf);
			}

			mixinClasses.put(mixinClass, mixinTargets);
		}

		inject(mixinClasses);
	}

	public void inject(Map<Class<?>, List<ClassFile>> mixinClasses) throws InjectionException
	{
		injectFields(mixinClasses);
		findShadowFields(mixinClasses);

		for (Class<?> mixinClass : mixinClasses.keySet())
		{
			try
			{
				for (ClassFile cf : mixinClasses.get(mixinClass))
				{
					// Make a new mixin ClassFile copy every time,
					// so they don't share Code references
					ClassFile mixinCf = loadClass(mixinClass);

					inject(mixinCf, cf, shadowFields);
				}
			}
			catch (IOException ex)
			{
				throw new InjectionException(ex);
			}
		}
	}

	/**
	 * Finds fields that are marked @Inject and inject them into the target
	 *
	 * @param mixinClasses
	 * @throws InjectionException
	 */
	private void injectFields(Map<Class<?>, List<ClassFile>> mixinClasses) throws InjectionException
	{
		// Inject fields, and put them in injectedFields if they can be used by other mixins
		for (Class<?> mixinClass : mixinClasses.keySet())
		{
			ClassFile mixinCf;

			try
			{
				mixinCf = loadClass(mixinClass);
			}
			catch (IOException ex)
			{
				throw new InjectionException(ex);
			}

			List<ClassFile> targetCfs = mixinClasses.get(mixinClass);

			for (ClassFile cf : targetCfs)
			{
				for (Field field : mixinCf.getFields())
				{
					Annotation inject = field.getAnnotations().find(INJECT);
					if (inject == null)
					{
						continue;
					}

					Field copy = new Field(cf, field.getName(), field.getType());
					copy.setAccessFlags(field.getAccessFlags());
					copy.setPublic();
					copy.setValue(field.getValue());
					cf.addField(copy);

					if (field.isStatic())
					{
						// Only let other mixins use this field if it's injected in a single target class
						if (targetCfs.size() == 1)
						{
							injectedFields.put(field.getName(), copy);
						}
					}
				}
			}

		}
	}

	/**
	 * Find fields which are marked @Shadow, and what they shadow
	 *
	 * @param mixinClasses
	 * @throws InjectionException
	 */
	private void findShadowFields(Map<Class<?>, List<ClassFile>> mixinClasses) throws InjectionException
	{
		// Find shadow fields
		// Injected static fields take precedence when looking up shadowed fields
		for (Class<?> mixinClass : mixinClasses.keySet())
		{
			ClassFile mixinCf;

			try
			{
				mixinCf = loadClass(mixinClass);
			}
			catch (IOException ex)
			{
				throw new InjectionException(ex);
			}

			for (Field field : mixinCf.getFields())
			{
				Annotation shadow = field.getAnnotations().find(SHADOW);
				if (shadow != null)
				{
					if (!field.isStatic())
					{
						throw new InjectionException("Can only shadow static fields");
					}

					String shadowName = shadow.getElement().getString(); // shadow this field

					Field injectedField = injectedFields.get(shadowName);
					if (injectedField != null)
					{
						// Shadow a field injected by a mixin
						shadowFields.put(field.getPoolField(), injectedField);
					}
					else
					{
						// Shadow a field already in the gamepack
						Field shadowField = findDeobField(shadowName);

						if (shadowField == null)
						{
							throw new InjectionException("Shadow of nonexistent field " + shadowName);
						}

						Field obShadow = inject.toObField(shadowField);
						assert obShadow != null;
						shadowFields.put(field.getPoolField(), obShadow);
					}
				}
			}
		}
	}

	private ClassFile loadClass(Class<?> clazz) throws IOException
	{
		try (InputStream is = clazz.getClassLoader().getResourceAsStream(clazz.getName().replace('.', '/') + ".class"))
		{
			ClassReader reader = new ClassReader(is);
			ClassFileVisitor cv = new ClassFileVisitor();

			reader.accept(cv, 0);

			return cv.getClassFile();
		}
	}

	private ClassFile findVanillaForInterface(Class<?> clazz)
	{
		for (ClassFile cf : inject.getVanilla().getClasses())
		{
			for (net.runelite.asm.pool.Class cl : cf.getInterfaces().getInterfaces())
			{
				if (cl.getName().equals(clazz.getName().replace('.', '/')))
				{
					return cf;
				}
			}
		}
		return null;
	}

	private Field findDeobField(String name)
	{
		for (ClassFile cf : inject.getDeobfuscated().getClasses())
		{
			for (Field f : cf.getFields())
			{
				if (f.getName().equals(name) && f.isStatic())
				{
					return f;
				}
			}
		}
		return null;
	}

	private void inject(ClassFile mixinCf, ClassFile cf, Map<net.runelite.asm.pool.Field, Field> shadowFields)
		throws InjectionException
	{
		ClassGroup group = new ClassGroup();
		group.addClass(mixinCf);
		group.initialize();

		// Keeps mappings between methods annotated with @Copy -> the copied method within the vanilla pack
		Map<net.runelite.asm.pool.Method, CopiedMethod> copiedMethods = new HashMap<>();

		// Handle the copy mixins first, so all other mixins know of the copies
		for (Method method : mixinCf.getMethods())
		{
			if (method.getAnnotations().find(COPY) != null)
			{
				Annotation copyAnnotation = method.getAnnotations().find(COPY);
				String deobMethodName = (String) copyAnnotation.getElement().getValue();

				ClassFile deobCf = inject.toDeobClass(cf);
				Method deobMethod = findDeobMethod(deobCf, deobMethodName);

				if (deobMethod == null)
				{
					throw new InjectionException("Failed to find the deob method " + deobMethodName + " for mixin " + mixinCf);
				}

				if (method.isStatic() != deobMethod.isStatic())
				{
					throw new InjectionException("Mixin method " + method + " should be " + (deobMethod.isStatic() ? "static" : "non-static"));
				}

				// Find the vanilla class where the method to copy is in
				String obClassName = DeobAnnotations.getObfuscatedName(deobMethod.getClassFile().getAnnotations());
				ClassFile obCf = inject.getVanilla().findClass(obClassName);

				String obMethodName = DeobAnnotations.getObfuscatedName(deobMethod.getAnnotations());
				Signature obMethodSignature = DeobAnnotations.getObfuscatedSignature(deobMethod);

				if (obMethodSignature == null)
				{
					obMethodSignature = deobMethod.getDescriptor();
				}

				Method obMethod = obCf.findMethod(obMethodName, obMethodSignature);
				if (obMethod == null)
				{
					throw new InjectionException("Failed to find the ob method " + obMethodName + " for mixin " + mixinCf);
				}

				Method copy = new Method(cf, "copy$" + deobMethodName, obMethodSignature);
				copy.setCode(obMethod.getCode());
				copy.setAccessFlags(obMethod.getAccessFlags());
				copy.setPublic();
				copy.getExceptions().getExceptions().addAll(obMethod.getExceptions().getExceptions());
				copy.getAnnotations().getAnnotations().addAll(obMethod.getAnnotations().getAnnotations());
				cf.addMethod(copy);

				boolean hasGarbageValue = deobMethod.getDescriptor().size() < obMethodSignature.size();
				copiedMethods.put(method.getPoolMethod(), new CopiedMethod(copy, hasGarbageValue));
			}
		}

		// Handle the rest of the mixin types
		for (Method method : mixinCf.getMethods())
		{
			if (method.getAnnotations().find(INJECT) != null)
			{
				Method copy = new Method(cf, method.getName(), method.getDescriptor());
				copy.setCode(method.getCode());
				copy.setAccessFlags(method.getAccessFlags());
				copy.setPublic();
				assert method.getExceptions().getExceptions().isEmpty();

				setOwnersToTargetClass(mixinCf, cf, copy, shadowFields, copiedMethods);

				cf.addMethod(copy);

				logger.debug("Injected mixin method {} to {}", copy, cf);
			}
			else if (method.getAnnotations().find(REPLACE) != null)
			{
				Annotation copyAnnotation = method.getAnnotations().find(REPLACE);
				String deobMethodName = (String) copyAnnotation.getElement().getValue();

				ClassFile deobCf = inject.toDeobClass(cf);
				Method deobMethod = findDeobMethod(deobCf, deobMethodName);

				if (deobMethod == null)
				{
					throw new InjectionException("Failed to find the deob method " + deobMethodName + " for mixin " + mixinCf);
				}

				if (method.isStatic() != deobMethod.isStatic())
				{
					throw new InjectionException("Mixin method " + method + " should be "
						+ (deobMethod.isStatic() ? "static" : "non-static"));
				}

				String obMethodName = DeobAnnotations.getObfuscatedName(deobMethod.getAnnotations());
				Signature obMethodSignature = DeobAnnotations.getObfuscatedSignature(deobMethod);

				// Deob signature is the same as ob signature
				if (obMethodSignature == null)
				{
					obMethodSignature = deobMethod.getDescriptor();
				}

				// Find the vanilla class where the method to copy is in
				String obClassName = DeobAnnotations.getObfuscatedName(deobMethod.getClassFile().getAnnotations());
				ClassFile obCf = inject.getVanilla().findClass(obClassName);

				Method obMethod = obCf.findMethod(obMethodName, obMethodSignature);
				obMethod.setCode(method.getCode());

				setOwnersToTargetClass(mixinCf, cf, obMethod, shadowFields, copiedMethods);
			}
		}
	}

	private void setOwnersToTargetClass(ClassFile mixinCf, ClassFile cf, Method method,
		Map<net.runelite.asm.pool.Field, Field> shadowFields,
		Map<net.runelite.asm.pool.Method, CopiedMethod> copiedMethods)
		throws InjectionException
	{
		ListIterator<Instruction> iterator = method.getCode().getInstructions().getInstructions().listIterator();

		while (iterator.hasNext())
		{
			Instruction i = iterator.next();

			if (i instanceof InvokeInstruction)
			{
				InvokeInstruction ii = (InvokeInstruction) i;

				CopiedMethod copiedMethod = copiedMethods.get(ii.getMethod());
				if (copiedMethod != null)
				{
					ii.setMethod(copiedMethod.obMethod.getPoolMethod());

					// Pass through garbage value if the method has one
					if (copiedMethod.hasGarbageValue)
					{
						int garbageIndex = copiedMethod.obMethod.isStatic()
							? copiedMethod.obMethod.getDescriptor().size() - 1
							: copiedMethod.obMethod.getDescriptor().size();

						iterator.previous();
						iterator.add(new ILoad(method.getCode().getInstructions(), garbageIndex));
						iterator.next();
					}
				}
				else if (ii.getMethod() != null && ii.getMethod().getClazz().getName().equals(mixinCf.getName()))
				{
					ii.setMethod(new net.runelite.asm.pool.Method(
						new net.runelite.asm.pool.Class(cf.getName()),
						ii.getMethod().getName(),
						ii.getMethod().getType()
					));
				}
			}
			else if (i instanceof FieldInstruction)
			{
				FieldInstruction fi = (FieldInstruction) i;

				Field shadowed = shadowFields.get(fi.getField());
				if (shadowed != null)
				{
					fi.setField(shadowed.getPoolField());
				}
				else if (fi.getField() != null && fi.getField().getClazz().getName().equals(mixinCf.getName()))
				{
					fi.setField(new net.runelite.asm.pool.Field(
						new net.runelite.asm.pool.Class(cf.getName()),
						fi.getField().getName(),
						fi.getField().getType()
					));
				}
			}

			verify(mixinCf, i);
		}
	}

	private Method findDeobMethod(ClassFile deobCf, String deobMethodName)
	{
		Method method = deobCf.findMethod(deobMethodName);

		if (method == null)
		{
			// Look for static methods if an instance method couldn't be found
			for (ClassFile deobCf2 : inject.getDeobfuscated().getClasses())
			{
				if (deobCf2 != deobCf)
				{
					method = deobCf2.findMethod(deobMethodName);

					if (method != null)
					{
						break;
					}
				}
			}
		}

		return method;
	}

	private void verify(ClassFile mixinCf, Instruction i) throws InjectionException
	{
		if (i instanceof FieldInstruction)
		{
			FieldInstruction fi = (FieldInstruction) i;

			if (fi.getField().getClazz().getName().equals(mixinCf.getName()))
			{
				if (i instanceof PutField || i instanceof GetField)
				{
					throw new InjectionException("Access to non static member field of mixin");
				}

				Field field = fi.getMyField();
				if (field != null && !field.isPublic())
				{
					throw new InjectionException("Static access to non public field " + field);
				}
			}
		}
		else if (i instanceof InvokeStatic)
		{
			InvokeStatic is = (InvokeStatic) i;

			if (is.getMethod().getClazz() != mixinCf.getPoolClass()
				&& is.getMethod().getClazz().getName().startsWith(MIXIN_BASE.replace(".", "/")))
			{
				throw new InjectionException("Invoking static methods of other mixins is not supported");
			}
		}
		else if (i instanceof InvokeDynamic)
		{
			// RS classes don't verify under java 7+ due to the
			// super() invokespecial being inside of a try{}
			throw new InjectionException("Injected bytecode must be Java 6 compatible");
		}
	}

	private static class CopiedMethod
	{
		private Method obMethod;
		private boolean hasGarbageValue;

		private CopiedMethod(Method obMethod, boolean hasGarbageValue)
		{
			this.obMethod = obMethod;
			this.hasGarbageValue = hasGarbageValue;
		}
	}
}
