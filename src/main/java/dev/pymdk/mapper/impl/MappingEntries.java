/*
 * This file is part of GrieferUtils (https://github.com/L3g7/GrieferUtils).
 * Copyright (c) L3g7.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package dev.pymdk.mapper.impl;

import dev.pymdk.mapper.Mapping;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static dev.pymdk.mapper.Mapping.OBFUSCATED;
import static dev.pymdk.mapper.Mapping.UNOBFUSCATED;

/**
 * Parsed mappings.
 */
public class MappingEntries {

	/**
	 * A class file mapping.
	 */
	public static class MappedClass extends MappedMember {

		String[] unobfBaseClasses;
		private transient MappedClass[] baseClasses;

		public MappedList<MappedField> fields;
		public MethodList methods;

		public MappedClass(String obf, String unobf) {
			super(obf, unobf);
			this.unobfName = unobf;
		}

		/**
		 * DFS recursive field lookup.
		 */
		public @Nullable MappedField getFieldRecursive(@NotNull String name, @NotNull Mapping mapping) {
			MappedField field = fields.get(name, mapping);
			if (field != null)
				return field;

			for (MappedClass baseClass : getBaseClasses()) {
				field = baseClass.getFieldRecursive(name, mapping);
				if (field != null)
					return field;
			}

			return null;
		}

		/**
		 * DFS recursive field lookup.
		 */
		public @Nullable MappedMethod getMethodRecursive(@NotNull String key, @NotNull Mapping mapping) {
			MappedMethod method = methods.get(key, mapping);
			if (method != null)
				return method;

			for (MappedClass baseClass : getBaseClasses()) {
				method = baseClass.getMethodRecursive(key, mapping);
				if (method != null)
					return method;
			}

			return null;
		}

		private @NotNull MappedClass[] getBaseClasses() {
			if (baseClasses != null)
				return baseClasses;

			if (unobfBaseClasses == null) {
				baseClasses = new MappedClass[0];
				return baseClasses;
			}

			baseClasses = new MappedClass[unobfBaseClasses.length];
			for (int i = 0; i < unobfBaseClasses.length; i++) {
				baseClasses[i] = LowLevelMapper.classes.get(unobfBaseClasses[i], UNOBFUSCATED);
				if (baseClasses[i] == null)
					throw new IllegalStateException("BC " + unobfBaseClasses[i] + " null");
			}

			return baseClasses;
		}

		public void create() {
			if (fields == null)
				fields = new MappedList<>();
			fields.create();

			if (methods == null)
				methods = new MethodList();
			methods.create();
		}

		@Override
		public String toString() {
			return "MappedClass{" +
					"obfName='" + obfName + '\'' +
					", unobfName='" + unobfName + '\'' +
					'}';
		}
	}

	/**
	 * A field mapping.
	 */
	public static class MappedField extends MappedMember {

		MappedField(String obf, String imd) {
			super(obf, imd);
		}

	}

	/**
	 * A method mapping.
	 */
	public static class MappedMethod extends MappedMember {

		MappedMethod(String obf, String imd) {
			super(obf, imd);
		}

	}

	/**
	 * A field or method mapping.
	 */
	public static class MappedMember {

		/**
		 * The obfuscated name of the member.
		 */
		final String obfName;

		/**
		 * The intermediary name of the member.
		 * This name stays consistent for every mapping version targeting the same minecraft version.
		 *
		 * @see Mapping#INTERMEDIARY
		 */
		final String imdName;

		/**
		 * The unobfuscated name of the member.
		 */
		String unobfName;

		/**
		 * The obfuscated descriptor of the member.
		 */
		String obfDesc;

		/**
		 * The unobfuscated descriptor of the member.
		 */
		String unobfDesc;

		MappedMember(String obfName, String imdName) {
			this.obfName = obfName;
			this.imdName = imdName;
		}

		/**
		 * @return The name in the specified mapping.
		 *         If the unobfuscated name is requested but does not exist,
		 *         the intermediary name will be returned instead.
		 */
		public @NotNull String getName(@NotNull Mapping mapping) {
			switch (mapping) {
				case OBFUSCATED:
					return obfName;
				case UNOBFUSCATED:
					if (unobfName != null)
						return unobfName;
					// fall-through
				default:
					return imdName;
			}
		}

		/**
		 * @return The descriptor in the specified mapping.
		 */
		public @NotNull String getDesc(@NotNull Mapping mapping) {
			if (mapping == OBFUSCATED)
				return obfDesc;

			return unobfDesc;
		}

		@Override
		public String toString() {
			return "MappedMember{" +
					"obfName='" + obfName + '\'' +
					", imdName='" + imdName + '\'' +
					", unobfName='" + unobfName + '\'' +
					'}';
		}
	}

	/**
	 * An {@link ArrayList} with a cache for every mapping type to achieve faster lookup.
	 */
	public static class MappedList<M extends MappedMember> extends ArrayList<M> {

		/**
		 * A member storage where the key is the obfuscated name.
		 */
		public transient final Map<String, M> obfMap = new HashMap<>();

		/**
		 * A member storage where the key is the intermediary name.
		 */
		public transient final Map<String, M> imdMap = new HashMap<>();

		/**
		 * A member storage where the key is the unobfuscated name.
		 */
		public transient final Map<String, M> unobfMap = new HashMap<>();

		public MappedList() {
			super(0);
		}

		/**
		 * populates the cache maps.
		 */
		public void create() {
			for (M member : this) {
				obfMap.put(member.obfName, member);
				imdMap.put(member.imdName, member);
				String unobfName = member.unobfName != null ? member.unobfName : member.imdName;
				unobfMap.put(unobfName, member);
				if (member instanceof MappedClass cls)
					cls.create();
			}
		}

		public @Nullable M get(@NotNull String key, @NotNull Mapping mapping) {
			return switch (mapping) {
				case OBFUSCATED -> obfMap.get(key);
				case INTERMEDIARY -> imdMap.get(key);
				default -> unobfMap.get(key);
			};
		}
	}

	/**
	 * A {@link MappedList} where the lookup keys include the corresponding method descriptors.
	 */
	public static class MethodList extends MappedList<MappedMethod> {

		/**
		 * populates the cache maps for faster mapping lookup.
		 */
		@Override
		public void create() {
			for (MappedMethod method : this) {
				obfMap.put(method.obfName + method.obfDesc, method);
				imdMap.put(method.imdName + method.unobfDesc, method);
				String unobfName = method.unobfName != null ? method.unobfName : method.imdName;
				unobfMap.put(unobfName + method.unobfDesc, method);
			}
		}

	}
}
