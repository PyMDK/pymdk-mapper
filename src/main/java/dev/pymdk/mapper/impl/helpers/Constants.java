package dev.pymdk.mapper.impl.helpers;

/**
 * Class file format definitions.
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html">Java Virtual Machine Specification, Chapter 4. The class File Format</a>
 */
public interface Constants {

	byte CONSTANT_CLASS = 7;
	byte CONSTANT_FIELDREF = 9;
	byte CONSTANT_METHODREF = 10;
	byte CONSTANT_INTERFACE_METHODREF = 11;
	byte CONSTANT_STRING = 8;
	byte CONSTANT_INTEGER = 3;
	byte CONSTANT_FLOAT = 4;
	byte CONSTANT_LONG = 5;
	byte CONSTANT_DOUBLE = 6;
	byte CONSTANT_NAME_AND_TYPE = 12;
	byte CONSTANT_UTF8 = 1;
	byte CONSTANT_METHOD_HANDLE = 15;
	byte CONSTANT_METHOD_TYPE = 16;
	// byte CONSTANT_DYNAMIC = 17;
	// byte CONSTANT_INVOKE_DYNAMIC = 18;
	byte CONSTANT_MODULE = 19;
	byte CONSTANT_PACKAGE = 20;

	int CONSTANT_POOL_SIZE_INDEX = 8;

}
