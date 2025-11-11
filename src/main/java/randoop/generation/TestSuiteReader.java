package randoop.generation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import randoop.DummyVisitor;
import randoop.main.GenInputsAbstract;
import randoop.sequence.ExecutableSequence;
import randoop.sequence.Sequence;
import randoop.sequence.SequenceParseException;
import randoop.test.DummyCheckGenerator;
import randoop.util.sequence.SequenceParser;

public class TestSuiteReader {

  public static List<Sequence> readSequencesFromFile(Path pathToFile) {
    List<Sequence> sequences = new ArrayList<>();
    if (pathToFile != null) {
      try {
        BufferedReader br = new BufferedReader(new FileReader(pathToFile.toFile()));
        String line;
        while ((line = br.readLine()) != null) {
          List<Sequence> suite_sequences = TestSuiteReader.readSequencesFromFile(line);
          sequences.addAll(suite_sequences);
        }
//      } catch (FileNotFoundException e) {
//        throw new RuntimeException(e);
      } catch (Exception e) {
        System.out.println(e.getMessage());
      }
      System.out.println("TestSuiteReader: Sequences read from file: " + sequences.size());
    }
    return sequences;
  }

  public static List<Sequence> readSequencesFromFile(String pathToFile) {
    List<Sequence> sequences = new ArrayList<>();
    if (pathToFile != null) {
      CompilationUnit cu = getCompilationUnit(new File(pathToFile));
      ClassOrInterfaceDeclaration clazz = null;
      for (Object n : cu.getChildNodes()) {
        if (n instanceof ClassOrInterfaceDeclaration)
          clazz = (ClassOrInterfaceDeclaration) n;
      }
      if (clazz != null) {
        for (MethodDeclaration md : getMethods(clazz)) {
          List<String> imports = getClassImports(cu);
          String code = replaceObjectConstruction(getMethodCode(md));
          Sequence seq = getSeqFromCode(code, imports, clazz.getNameAsString());
          sequences.add(seq);
        }
      }
      System.out.println("TestSuiteReader: Sequences read from file: " + sequences.size());
    }
    return sequences;
  }

  public static List<ExecutableSequence> readFromFile(String pathToFile) {
    return readFromFile(new File(pathToFile));
  }

  private static List<ExecutableSequence> readFromFile(File file) {
    List<ExecutableSequence> sequences = new ArrayList<>();
    CompilationUnit cu = getCompilationUnit(file);
    ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) cu.getChildNodes().get(4);
    for (MethodDeclaration md : getMethods(clazz)) {
      List<String> imports = getClassImports(cu);
      String code = replaceObjectConstruction(getMethodCode(md));
      Sequence seq = getSeqFromCode(code, imports, clazz.getNameAsString());
      sequences.add(executeSeq(seq));
    }
    return sequences;
  }

  private static CompilationUnit getCompilationUnit(File f) {
    JavaParser jp = new JavaParser();
    try {
      ParseResult<CompilationUnit> result = jp.parse(f);
      if (result.getResult().isPresent()) {
        return result.getResult().get();
      }
      throw new IllegalStateException("Compilation Unit is not present");
    } catch (FileNotFoundException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static String getMethodCode(MethodDeclaration md) {
    if (!md.getBody().isPresent()) {
      throw new RuntimeException("Method declaration body is not present");
    }
    NodeList<Statement> statements = md.getBody().get().getStatements();
    return new ArrayList<>(statements)
        .stream().map(Objects::toString).collect(Collectors.joining("\n"));
  }

  private static List<String> getClassImports(CompilationUnit cu) {
    return cu.getImports().stream().map(imp -> imp.getNameAsString()).collect(Collectors.toList());
  }

  private static List<MethodDeclaration> getMethods(ClassOrInterfaceDeclaration clazz) {
    List<MethodDeclaration> methods = new ArrayList<>();
    for (Object m: clazz.getMembers().stream().collect(Collectors.toList())){
      if (m instanceof MethodDeclaration) {
        methods.add((MethodDeclaration) m);
      }
    }
    return methods;
//    return clazz.getMembers().stream().map(m -> (MethodDeclaration) m).collect(Collectors.toList());
  }

  private static String replaceObjectConstruction(String code) {
    // TODO: Maybe we could try to get other objects, instead of only replace them with strings
    return code.replaceAll("Object\\(\\)", "String()");
  }

  private static Sequence getSeqFromCode(String code, List<String> imports, String forClass) {
    try {
      return SequenceParser.codeToSequence(code, imports, forClass);
    } catch (SequenceParseException e) {
      throw new RuntimeException("Could not generate sequence for code: \n\n" + code);
    }
  }

  private static ExecutableSequence executeSeq(Sequence seq) {
    ExecutableSequence execSeq = new ExecutableSequence(seq);
    execSeq.execute(new DummyVisitor(), new DummyCheckGenerator());
    return execSeq;
  }
}
