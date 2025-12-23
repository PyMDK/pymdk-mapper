package dev.pymdk.mapper;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LocalVariableNode;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalNameGenerator {

	private final Map<String, AtomicInteger> generatedNames = new HashMap<>();

	public void rename(LocalVariableNode local) {
		if (!local.name.startsWith("$$"))
			return;

		AtomicInteger count = generatedNames.computeIfAbsent(local.desc, k -> new AtomicInteger());

		String internalName = Type.getType(local.desc).getClassName();
		int lastDot = Math.max(internalName.lastIndexOf('.'), internalName.lastIndexOf('$'));
		if (lastDot != -1)
			internalName = internalName.substring(lastDot + 1);

		internalName = Character.toLowerCase(internalName.charAt(0)) + internalName.substring(1);
		while (internalName.endsWith("[]"))
			internalName = internalName.substring(0, internalName.length() - 2) + "A";

		local.name = internalName + count.getAndIncrement();
	}

}
