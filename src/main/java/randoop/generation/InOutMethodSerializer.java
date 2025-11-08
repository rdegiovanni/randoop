package randoop.generation;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.thoughtworks.xstream.XStream;

import randoop.InOutObjectsCollector;
import randoop.operation.TypedClassOperation;
import randoop.operation.TypedOperation;
import randoop.reflection.SingleMethodMatcher;
import randoop.sequence.ExecutableSequence;
import randoop.types.PrimitiveType;
import randoop.types.Type;
import randoop.types.TypeTuple;

public class InOutMethodSerializer implements IEventListener {

	private Pattern methodPattern;
	private SingleMethodMatcher methodMatcher;
	private TypedClassOperation operation;
	private String outputFolder;
	private XStream xstream;
	private List<ObjectOutputStream> inOoss;
	private List<ObjectOutputStream> outOoss;
	private InOutObjectsCollector inOutCollector;
	private int inObjs = -1;
	private int outObjs = -1;
	private int tuplesGenerated = 0;
	private boolean first = true;

	public InOutMethodSerializer(XStream xstream, Pattern method, String outputFolder, InOutObjectsCollector inOutCollector) {
		this.xstream = xstream;
		this.methodPattern = method;
		this.methodMatcher = new SingleMethodMatcher(methodPattern);
		this.outputFolder = outputFolder;
		this.inOutCollector = inOutCollector;
	}
	
	@Override
	public void explorationStart() { 

	}

	@Override
	public void explorationEnd() {
		closeStream(inOoss);
		closeStream(outOoss);
		String op = "";
		if (operation != null)
			op = operation.toParsableString();
		System.out.println(String.format(
				"\nInOutMethodSerializer: Generated %d input/output tuples for %s method.", 
				tuplesGenerated,
				op));
	}

	@Override
	public void generationStepPre() { 

	}

	@Override
	public void generationStepPost(ExecutableSequence s) {
		if (s == null || !s.isNormalExecution())
			return;

		
		TypedOperation lastOp = s.sequence.getStatement(s.sequence.size() - 1).getOperation();
		if (!(lastOp instanceof TypedClassOperation)) 
			return;
		TypedClassOperation typedLastOp = (TypedClassOperation) lastOp;
		if (!methodMatcher.matches(typedLastOp)) 
			return;
		
		List<Object> inputs = inOutCollector.getInputs();
		List<Object> outputs = inOutCollector.getOutputs();
		if (first) {
			operation = typedLastOp;
			inObjs = inputs.size();
			outObjs = outputs.size();
			inOoss = createStream(inObjs, "in");
			outOoss = createStream(outObjs, "out");
			first = false;
		}
		else 
			consistencyChecks(s, typedLastOp, inputs, outputs);

		writeObjectsWideningPrimitiveInputs(inputs, 
				inOoss, 
				typedLastOp.getInputTypes(), 
				typedLastOp.getOutputType());
		writeObjectsWideningPrimitiveInputs(outputs, 
				outOoss, 
				typedLastOp.getInputTypes(),
				typedLastOp.getOutputType());
		tuplesGenerated++;
	}

	private void consistencyChecks(ExecutableSequence s, TypedClassOperation typedLastOp, List<Object> inputs,
			List<Object> outputs) {
		// Consistency checks
		assert operation.equals(typedLastOp) : 
			String.format("Serializing inputs/outputs for two different methods not allowed. "
					+ "\nMatching method 1: %s"
					+ "\nMatching method 2: %s", 
					operation.toParsableString(), 
					typedLastOp.toParsableString());
		assert inObjs == inputs.size() : 
			String.format("Serializing %d inputs but current operation has %d inputs."
					+ "\nSequence: ", 
					inObjs, 
					inputs.size(), 
					s.toCodeString());
		assert outObjs == outputs.size() : 
			String.format("Serializing %d outputs but current operation has %d outputs."
					+ "\nSequence: ", 
					outObjs, 
					outputs.size(), 
					s.toCodeString());
	}

	@Override
	public void progressThreadUpdate() { }

	@Override
	public boolean shouldStopGeneration() {
		return false;
	}
	
	
	private List<ObjectOutputStream> createStream(int n, String inOut) {
		List<ObjectOutputStream> loos = new ArrayList<>();
		for (int k = 0; k < n; k++) {
			String currFile = outputFolder + "/" + inOut + String.valueOf(k) + ".xml";
			try {
				loos.add(xstream.createObjectOutputStream(
						   new FileOutputStream(currFile)));
			} catch (IOException e) {
				throw new Error("Cannot create serial file: " + currFile);
			}
		}
		return loos;
	}
	
	private void writeObjects(List<Object> objs, List<ObjectOutputStream> loos) {
		for (int k = 0; k < objs.size(); k++) {
			try {
				loos.get(k).writeObject(objs.get(k));	
			} catch (IOException e) {
				throw new Error("Cannot serialize object: " + objs.get(k).toString());
			}
		}
	}
	
	private void writeObjectsWideningPrimitiveInputs(List<Object> objs, List<ObjectOutputStream> loos, 
			TypeTuple inTypes, Type outType) {
		for (int k = 0; k < objs.size(); k++) {
			Object currObj = objs.get(k);
			ObjectOutputStream currStream = loos.get(k);
			Type type = null;
			if (k < inTypes.size()) 
				type = inTypes.get(k);
			else
				type = outType;
			// Randoop might instantiate primitive parameters with values of different types
			// so we need to convert ('widen') the values to the types defined in the method
			// before serializing them
			// Warning: We box all primitive values here
			Object objWithMethodType = TypeConversions.widenPrimitiveValueToParameterType(currObj, type);
			try {
				currStream.writeObject(objWithMethodType);	
			} catch (IOException e) {
				throw new Error("Cannot serialize object: " + currObj.toString());
			}
		}
	}
	
	private void closeStream(List<ObjectOutputStream> loos) {
		if (loos != null) {
			for (ObjectOutputStream oos : loos) {
				try {
					oos.close();
				} catch (IOException e) {
					throw new Error("Cannot close files in folder: " + outputFolder);
				}
			}
		}
	}
	

}


class TypeConversions {
	
	public static Object widenPrimitiveValueToParameterType(Object obj, Type type) {
		if (obj == null) return obj;
		
		if (type.isPrimitive() || type.isBoxedPrimitive()) {
			if (type.isPrimitive()) {
				PrimitiveType pType = (PrimitiveType) type;
				type = pType.toBoxedPrimitive();
			}
			return makeWideningConversion(obj, type);
		}
		
		return obj;
	}

	// Widening conversions:
	//   byte -> short
	//   short -> int
	//   char -> int
	//   int -> long
	//   long -> float
	//   float -> double
	private static Object makeWideningConversion(Object obj, Type type) {
		Class<?> objClass = obj.getClass();
		Class<?> typeClass = type.getRuntimeClass();
		if (objClass.equals(typeClass) || objClass.equals(Double.class))
			return obj;
		
		if (objClass.equals(Float.class)) {
			Float b = (Float) obj;
			if (typeClass.equals(Double.class))
				return (double) b.floatValue();		
		}
		if (objClass.equals(Long.class)) {
			Long b = (Long) obj;
			if (typeClass.equals(Float.class))
				return (float) b.longValue();
			if (typeClass.equals(Double.class))
				return (double) b.longValue();	
		}
		if (objClass.equals(Integer.class)) {
			Integer b = (Integer) obj;
			if (typeClass.equals(Long.class))
				return (long) b.intValue();
			if (typeClass.equals(Float.class))
				return (float) b.intValue();
			if (typeClass.equals(Double.class))
				return (double) b.intValue();	
		}
		if (objClass.equals(Character.class)) {
			Character b = (Character) obj;
			if (typeClass.equals(Integer.class))
				return (int) b.charValue();
			if (typeClass.equals(Long.class))
				return (long) b.charValue();
			if (typeClass.equals(Float.class))
				return (float) b.charValue();
			if (typeClass.equals(Double.class))
				return (double) b.charValue();		
		}
		if (objClass.equals(Short.class)) {
			Short b = (Short) obj;
			if (typeClass.equals(Character.class))
				return (char) b.shortValue();
			if (typeClass.equals(Integer.class))
				return (int) b.shortValue();
			if (typeClass.equals(Long.class))
				return (long) b.shortValue();
			if (typeClass.equals(Float.class))
				return (float) b.shortValue();
			if (typeClass.equals(Double.class))
				return (double) b.shortValue();
		}
		if (objClass.equals(Byte.class)) {
			Byte b = (Byte) obj;
			if (typeClass.equals(Short.class))
				return (short) b.byteValue();
			if (typeClass.equals(Character.class))
				return (char) b.byteValue();
			if (typeClass.equals(Integer.class))
				return (int) b.byteValue();
			if (typeClass.equals(Long.class))
				return (long) b.byteValue();
			if (typeClass.equals(Float.class))
				return (float) b.byteValue();
			if (typeClass.equals(Double.class))
				return (double) b.byteValue();
		}
		
		throw new Error(
				String.format("Widening failed: Could not convert value %s of type %s to type %s", 
						obj.toString(),
						objClass.getName(),
						typeClass.getName()));
	}

}
