/*
 * This file is part of GrieferUtils (https://github.com/L3g7/GrieferUtils).
 * Copyright (c) L3g7.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package dev.pymdk.mapper;

public enum Mapping {

	/**
	 * The original, obfuscated names.
	 */
	OBFUSCATED,

	/**
	 * The intermediary names.
	 * They stay consistent for every mapping version targeting the same Minecraft version.
	 */
	INTERMEDIARY,

	/**
	 * The deobfuscated names.
	 * If no mapping exists for a specific member, the intermediary name is used.
	 */
	UNOBFUSCATED

}
