package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.adelheid.oneiro.api.LuaConstants;
import org.adelheid.oneiro.api.LuaGlobal;

/**
 * Writes the names of a constant namespace as a chain of chunk classes, straight to class files: {@code config.Loc}'s
 * 137k names are more than one class can hold, and compiling half a million lines of Java to declare them would be
 * slow. Each chunk holds {@link JavaBindings#CHUNK_SIZE} names, extends the one before, and is package-private, so it
 * stays out of completion while the namespace class, which extends the last, inherits every name. Each chunk carries
 * {@code @LuaGlobal} with its namespace's Lua path, because a name resolves to the chunk that declares it, and
 * {@code @LuaConstants}, because each name is {@link LuaConstants#PREFIX} and its Lua name, which Java can always
 * spell.
 *
 * <p>The names of {@code id} are {@code int} constants, so they work as {@code case} labels; the names of
 * {@code config} and {@code var} are {@code final} fields of their config type without a value, because they exist
 * only in Lua.
 */
final class ConstantChunks {

	private static final ClassDesc LUA_GLOBAL = ClassDesc.of(LuaGlobal.class.getName());
	private static final ClassDesc LUA_CONSTANTS = ClassDesc.of(LuaConstants.class.getName());
	private static final int NAME_FLAGS = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;

	private ConstantChunks() {
	}

	/** Writes the namespace's chunk classes under {@code classes}, in the package's folder. */
	static void write(JavaBindings.ConstantNamespace namespace, Path classes) throws IOException {
		Path folder = classes.resolve(JavaBindings.PACKAGE.replace('.', '/'));
		Files.createDirectories(folder);
		String element = namespace.elementBinaryName();
		ClassDesc elementType = element == null ? ConstantDescs.CD_int : ClassDesc.of(element);
		List<CsvConstants.Row> rows = namespace.rows();
		ClassDesc superclass = ConstantDescs.CD_Object;
		for (int chunk = 1; chunk <= namespace.chunkCount(); chunk++) {
			String name = namespace.chunkPrefix() + chunk;
			ClassDesc self = ClassDesc.of(JavaBindings.PACKAGE + "." + name);
			ClassDesc parent = superclass;
			List<CsvConstants.Row> names = rows.subList((chunk - 1) * JavaBindings.CHUNK_SIZE,
					Math.min(rows.size(), chunk * JavaBindings.CHUNK_SIZE));
			byte[] bytes = ClassFile.of().build(self, builder -> {
				builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
				// Package-private and abstract: only the namespace class, in the same package, extends it
				builder.withFlags(ClassFile.ACC_SUPER | ClassFile.ACC_ABSTRACT);
				builder.withSuperclass(parent);
				builder.with(RuntimeInvisibleAnnotationsAttribute.of(
						Annotation.of(LUA_GLOBAL, AnnotationElement.ofString("value", namespace.luaPath())),
						Annotation.of(LUA_CONSTANTS)));
				builder.withMethod(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, 0,
						method -> method.withCode(code -> {
					code.aload(0);
					code.invokespecial(parent, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
					code.return_();
				}));
				for (CsvConstants.Row row : names) {
					String javaName = LuaConstants.PREFIX + row.name();
					if (element == null) {
						builder.withField(javaName, elementType, field -> field.withFlags(NAME_FLAGS)
								.with(ConstantValueAttribute.of((int) row.id())));
					} else {
						builder.withField(javaName, elementType, NAME_FLAGS);
					}
				}
			});
			Files.write(folder.resolve(name + ".class"), bytes);
			superclass = self;
		}
	}
}
