package dev.pymdk.mapper.impl;

import org.jetbrains.annotations.NotNull;

import static dev.pymdk.mapper.impl.LowLevelMapper.dbgAssert;
import static java.lang.Math.min;

/**
 * Port of <a href="https://github.com/PyMDK/PyMDK">PyMDK</a>'s signature parser with inlined writing.
 */
public class SignatureMapper {

	private final StringBuilder out;
	private final String text;
	private int position;
	private final int end;

	private SignatureMapper(String text) {
		this.out = new StringBuilder(text.length());
		this.text = text;
		this.position = 0;
		this.end = text.length();
	}

	public static @NotNull String mapSignature(@NotNull String desc, @NotNull AttributeHolder attributeHolder) {
		SignatureMapper mapper = new SignatureMapper(desc);
		attributeHolder.mapSignature(mapper);
		dbgAssert(mapper.exhausted());
		return mapper.out.toString();
	}

	private char peek() {
		return text.charAt(position);
	}

	private void skip(int count) {
		position += count;
	}

	private boolean exhausted() {
		return position >= end;
	}

	private void copySkip() {
		out.append(text.charAt(position++));
	}

	private void mapIdentifier(@NotNull String identifier) {
		MappingEntries.MappedClass mapped = LowLevelMapper.classes.get(identifier, LowLevelMapper.SOURCE_MAPPING);
		if (mapped == null)
			out.append(identifier);
		else
			out.append(mapped.getName(LowLevelMapper.TARGET_MAPPING));
	}

	/**
	 * <pre>
	 * JavaTypeSignature:
	 *   ReferenceTypeSignature
	 *   BaseType
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-JavaTypeSignature">4.7.9.1. Signatures</a>
	 */
	private void mapJavaTypeSignature() {
		char c = peek();
		switch (c) {
			case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> copySkip(); // c
			default -> mapReferenceTypeSignature();
		}
	}

	/**
	 * <pre>
	 * ReferenceTypeSignature:
	 *   ClassTypeSignature
	 *   TypeVariableSignature
	 *   ArrayTypeSignature
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-ReferenceTypeSignature">4.7.9.1. Signatures</a>
	 */
	private void mapReferenceTypeSignature() {
		switch (peek()) {
			case 'L' -> mapClassTypeSignature();
			case 'T' -> mapTypeVariableSignature();
			case '[' -> mapArrayTypeSignature();
			default -> throw new IllegalArgumentException("Invalid signature '" + text + "'");
		}
	}

	/**
	 * <pre>
	 * ClassTypeSignature:
	 *   L [PackageSpecifier] SimpleClassTypeSignature {ClassTypeSignatureSuffix} ;
	 *
	 * PackageSpecifier:
	 *   Identifier / {PackageSpecifier}
	 *
	 * ClassTypeSignatureSuffix:
	 *   . SimpleClassTypeSignature
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-ClassTypeSignature">4.7.9.1. Signatures</a>
	 */
	private void mapClassTypeSignature() {
		copySkip(); // "L"

		// Read [PackageSpecifier] and identifier (optimized)
		String view = text.substring(position);
		int semicolon = view.indexOf(';');
		view = view.substring(0, semicolon);
		int viewEnd = semicolon;
		int idx;
		if ((idx = view.indexOf('<')) != -1)
			viewEnd = idx;

		if ((idx = view.indexOf('.')) != -1)
			viewEnd = min(viewEnd, idx);

		if (viewEnd != semicolon)
			view = view.substring(0, viewEnd);

		mapIdentifier(view);
		skip(viewEnd);

		// Read [TypeArguments]
		if (peek() == '<') {
			copySkip(); // "<"
			while (peek() != '>')
				mapTypeArgument();
			copySkip(); // ">"
		}

		// Read {ClassTypeSignatureSuffix}
		while (peek() == '.') {
			copySkip(); // "."
			mapSimpleClassTypeSignature();
		}

		copySkip(); // ";"
	}

	/**
	 * <pre>
	 * SimpleClassTypeSignature:
	 *   Identifier [TypeArguments]
	 *
	 * TypeArguments:
	 *   < TypeArgument {TypeArgument} >
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-SimpleClassTypeSignature">4.7.9.1. Signatures</a>
	 */
	private void mapSimpleClassTypeSignature() {
		// Read Identifier
		int endPos = text.substring(position).indexOf(';') + position;
		String identifier = text.substring(position, endPos);
		int typeArgsStartPos;
		if ((typeArgsStartPos = identifier.indexOf('<')) != -1) {
			identifier = identifier.substring(0, typeArgsStartPos);
			position += typeArgsStartPos;
		} else
			position = endPos;

		mapIdentifier(identifier);

		// Read [TypeArguments]
		if (peek() == '<') {
			copySkip(); // "<"
			while (peek() != '>')
				mapTypeArgument();
			copySkip(); // ">"
		}
	}

	/**
	 * <pre>
	 * TypeArgument:
	 *   [WildcardIndicator] ReferenceTypeSignature
	 *   *
	 *
	 * WildcardIndicator:
	 *   +
	 *   -
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-TypeArgument">4.7.9.1. Signatures</a>
	 */
	private void mapTypeArgument() {
		char wildcard = peek();

		// Read 2nd case
		if (wildcard == '*') {
			copySkip(); // "*"
			return;
		}

		// Read 1st case
		switch (wildcard) {
			case '+' -> {
				copySkip(); // "+"
				mapReferenceTypeSignature();
			}
			case '-' -> {
				copySkip(); // "-"
				mapReferenceTypeSignature();
			}
			default -> mapReferenceTypeSignature();
		}
	}

	/**
	 * <pre>
	 * TypeVariableSignature:
	 *   T Identifier ;
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-TypeVariableSignature">4.7.9.1. Signatures</a>
	 */
	private void mapTypeVariableSignature() {
		copySkip(); // "T"

		int endPos = position + text.substring(position).indexOf(";");
		out.append(text, position, endPos);
		position = endPos;

		copySkip(); // ";"
	}

	/**
	 * <pre>
	 * ArrayTypeSignature:
	 *   [ JavaTypeSignature
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-ArrayTypeSignature">4.7.9.1. Signatures</a>
	 */
	private void mapArrayTypeSignature() {
		copySkip(); // "["
		mapJavaTypeSignature();
	}

	/**
	 * <pre>
	 * ClassSignature:
	 *   [TypeParameters] SuperclassSignature {SuperinterfaceSignature}
	 *
	 * SuperclassSignature:
	 *   ClassTypeSignature
	 *
	 * SuperinterfaceSignature:
	 *   ClassTypeSignature
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-ClassSignature">4.7.9.1. Signatures</a>
	 */
	@SuppressWarnings("WhileCanBeDoWhile")
	private void mapClassSignature() {
		// Read [TypeParameters]
		if (peek() == '<') {
			copySkip();  // "<"
			while (peek() != '>')
				mapTypeParameter();
			copySkip();  // ">"
		}

		// Read SuperclassSignature
		mapClassTypeSignature();

		// Read {SuperinterfaceSignature}
		while (!exhausted() && peek() == 'L')
			mapClassTypeSignature();
	}

	/**
	 * <pre>
	 * TypeParameter:
	 *   Identifier ClassBound {InterfaceBound}
	 *
	 * ClassBound:
	 *   : [ReferenceTypeSignature]
	 *
	 * InterfaceBound:
	 *   : ReferenceTypeSignature
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-TypeParameter">4.7.9.1. Signatures</a>
	 */
	private void mapTypeParameter() {
		// Read Identifier
		int endPos = position + text.substring(position).indexOf(":") + 1;
		out.append(text, position, endPos);
		position = endPos;

		// Read ClassBound
		switch (peek()) {
			case 'L', 'T', '[' -> mapReferenceTypeSignature();
		}

		// Read {InterfaceBound}
		while (peek() == ':') {
			copySkip(); // ":"
			mapReferenceTypeSignature();
		}
	}

	/**
	 * <pre>
	 * MethodSignature:
	 *   [TypeParameters] ( {JavaTypeSignature} ) Result {ThrowsSignature}
	 *
	 * TypeParameters:
	 *   < TypeParameter {TypeParameter} >
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-MethodSignature">4.7.9.1. Signatures</a>
	 */
	private void mapMethodSignature() {
		// Read [TypeParameters]
		if (peek() == '<') {
			copySkip(); // "<"
			while (peek() != '>')
				mapTypeParameter();
			copySkip(); // ">"
		}

		copySkip(); // "("

		// Read {JavaTypeSignature}
		while (peek() != ')')
			mapJavaTypeSignature();

		copySkip(); // ")"

		// Read Result
		mapResult();

		// Read {ThrowsSignature}1
		while (!exhausted() && peek() == '^') {
			copySkip(); // "^"
			mapThrowsSignature();
		}
	}

	/**
	 * <pre>
	 * Result:
	 *   JavaTypeSignature
	 *   VoidDescriptor
	 *
	 * VoidDescriptor:
	 *   V
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-MethodSignature">4.7.9.1. Signatures</a>
	 */
	private void mapResult() {
		if (peek() == 'V')
			copySkip(); // "V"
		else
			mapJavaTypeSignature();
	}

	/**
	 * <pre>
	 * ThrowsSignature:
	 *   ^ ClassTypeSignature
	 *   ^ TypeVariableSignature
	 * </pre>
	 *
	 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-MethodSignature">4.7.9.1. Signatures</a>
	 */
	private void mapThrowsSignature() {
		if (peek() == 'L')
			mapClassTypeSignature();
		else
			mapTypeVariableSignature();
	}

	public enum AttributeHolder {
		FIELD {
			@Override
			void mapSignature(@NotNull SignatureMapper mapper) {
				mapper.mapReferenceTypeSignature();
			}
		},
		METHOD {
			@Override
			void mapSignature(@NotNull SignatureMapper mapper) {
				mapper.mapMethodSignature();
			}
		},
		CLASS {
			@Override
			void mapSignature(@NotNull SignatureMapper mapper) {
				mapper.mapClassSignature();
			}
		},
		CODE {
			@Override
			void mapSignature(@NotNull SignatureMapper reader) {
				throw new UnsupportedOperationException("Unexpected signature");
			}
		};

		abstract void mapSignature(@NotNull SignatureMapper reader);
	}

}
