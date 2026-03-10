package randoop.generation;

import randoop.sequence.Sequence;
import randoop.sequence.SequenceParseException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

/**
 * Reads a developer-written JUnit test suite and uses an LLM (Claude or GPT)
 * to generate Randoop-parseable sequences from it, suitable for use as seeds.
 *
 * <p>Rather than attempting a fragile AST-to-sequence transformation, the test
 * source is sent to an LLM with a carefully structured prompt. The LLM is
 * instructed to produce simple, straight-line sequences that Randoop's
 * {@link Sequence#parse(String)} can reliably accept.
 *
 * <p>Supported providers:
 * <ul>
 *   <li>{@code CLAUDE} — Anthropic Claude (claude-3-5-sonnet latest)</li>
 *   <li>{@code GPT}    — OpenAI GPT-4o</li>
 * </ul>
 *
 * <p>Required environment variables (depending on provider):
 * <ul>
 *   <li>{@code ANTHROPIC_API_KEY} for Claude</li>
 *   <li>{@code OPENAI_API_KEY} for GPT</li>
 * </ul>
 */
public class LLMBasedTestSuiteReader {

//    private static final Logger LOG = Logger.getLogger(LLMBasedTestSuiteReader.class.getName());

    // -----------------------------------------------------------------------
    // Provider enum
    // -----------------------------------------------------------------------

    /**
     * The LLM provider to use for sequence generation.
     */
    public enum LlmProvider {
        /** Anthropic Claude (claude-3-5-sonnet-20241022). */
        CLAUDE,
        /** OpenAI GPT-4o. */
        GPT
    }

    // -----------------------------------------------------------------------
    // API constants
    // -----------------------------------------------------------------------

    private static final String ANTHROPIC_API_URL  =
            "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_MODEL    =
            "claude-3-5-sonnet-20241022";
    private static final String ANTHROPIC_VERSION  =
            "2023-06-01";

    private static final String OPENAI_API_URL     =
            "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_MODEL       =
            "gpt-5";

    // Maximum tokens to request in the LLM response.
    private static final int MAX_TOKENS = 4096;

    // Delimiter the LLM is asked to place around each sequence block.
    private static final String SEQ_START = "===SEQ_START===";
    private static final String SEQ_END   = "===SEQ_END===";

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public static List<Sequence> readSequencesFromFile(Path sequencesFilePath, Path classUnderTestPath,
                                                       Path testSuitePath){
        List<Sequence> sequences = new ArrayList<>();
        if (sequencesFilePath != null) {
            try {
                StringBuilder randoopSequences = new StringBuilder();
                if (java.nio.file.Files.exists(sequencesFilePath)) {
                    randoopSequences = new StringBuilder(new String(
                            Files.readAllBytes(sequencesFilePath),
                            StandardCharsets.UTF_8));
                } else if (classUnderTestPath != null && testSuitePath != null) {
                    String classUnderTestSource = new String(
                            java.nio.file.Files.readAllBytes(classUnderTestPath),
                            java.nio.charset.StandardCharsets.UTF_8);
                    BufferedReader br = new BufferedReader(new FileReader(testSuitePath.toFile()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        String testSuiteSource = new String(
                                java.nio.file.Files.readAllBytes(Paths.get(line)),
                                java.nio.charset.StandardCharsets.UTF_8);
                        randoopSequences.append(testToSequence(classUnderTestSource, testSuiteSource, LlmProvider.GPT));
                    }
                    if (randoopSequences.length() > 0)
                        writeFile(sequencesFilePath,randoopSequences.toString());
                }
                if (randoopSequences.length() > 0) {
                    List<Sequence> suite_sequences = sequencesToSequence(randoopSequences.toString());
                    sequences.addAll(suite_sequences);
                }

            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
            System.out.println("LLMBasedTestSuiteReader: Sequences read from file: " + sequences.size());
        }
        return sequences;
    }

    /**
     * Reads the JUnit test suite at {@code testSuitePath}, sends it together
     * with the source code of the class under test at {@code classUnderTestPath}
     * to the specified LLM, and returns the list of Randoop {@link Sequence}
     * objects that the LLM generated.
     *
     * <p>The LLM uses the class under test as the authoritative source for
     * method signatures, constructor parameters, and return types, and uses
     * the JUnit test suite only as an example of how the class is exercised.
     * The generated sequences do not need to reproduce the original tests exactly.
     *
     * @param classUnderTestSource Java source file of the class under test
     * @param testSuiteSource      Java source file of the JUnit test suite
     * @param provider           the LLM provider ({@code CLAUDE} or {@code GPT})
     * @return a (possibly empty) list of Randoop sequences
     * @throws IOException if either file cannot be read or the HTTP call fails
     */
    public static String testToSequence(String classUnderTestSource,
                                                       String testSuiteSource,
                                                       LlmProvider provider)
            throws IOException {

        // JDK 1.8 compatible file reading.
//        String classUnderTestSource = new String(
//                java.nio.file.Files.readAllBytes(classUnderTestPath),
//                java.nio.charset.StandardCharsets.UTF_8);
//
//        String testSuiteSource = new String(
//                java.nio.file.Files.readAllBytes(testSuitePath),
//                java.nio.charset.StandardCharsets.UTF_8);

        String prompt = buildPrompt(classUnderTestSource, testSuiteSource);
        String rawReply;

        // JDK 1.8 compatible: if/else instead of switch expression.
        if (provider == LlmProvider.CLAUDE) {
            rawReply = callClaude(prompt);
        } else if (provider == LlmProvider.GPT) {
            rawReply = callGpt(prompt);
        } else {
            throw new IllegalArgumentException("Unknown LLM provider: " + provider);
        }

        System.out.println("Raw LLM reply:\n" + rawReply);
        return rawReply;
    }

    public static List<Sequence> sequencesToSequence(String rawReply) {
        List<String> blocks   = extractSequenceBlocks(rawReply);
        List<Sequence> result = new ArrayList<Sequence>();

        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i).trim();
            if (block.isEmpty()) continue;

            List<String> lines = parseBlockIntoLines(block);
            if (lines.isEmpty()) continue;

            System.out.println("Attempting to parse sequence " + i + " with "
                    + lines.size() + " line(s):");
            for (int j = 0; j < lines.size(); j++) {
                System.out.println("  [" + j + "] " + lines.get(j));
            }

            try {
                Sequence seq = Sequence.parse(lines);
                result.add(seq);
                System.out.println("Sequence " + i + " parsed successfully ("
                        + seq.size() + " statement(s)).");
            } catch (SequenceParseException | Error e) {
                System.out.println(Level.WARNING +
                        " Sequence " + i + " failed to parse, skipping: "
                                + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Writes the given content string to the specified file path.
     * Creates all necessary parent directories and the file if they do not exist.
     *
     * @param path the target file path (e.g., "output/logs/result.txt")
     * @param content  the string content to write into the file
     * @throws IOException if an I/O error occurs during directory creation or file writing
     */
    public static void writeFile(Path path , String content) throws IOException {
//        Path path = Paths.get(filePath);
        // Create parent directories if they do not exist
        try {
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            // Write content to file using OutputStreamWriter for explicit UTF-8 encoding

//            BufferedWriter writer = new BufferedWriter(
//                new OutputStreamWriter(
//                        Files.newOutputStream(path.toFile().toPath()), StandardCharsets.UTF_8));
//            writer.write(content);
            Files.writeString(path, content,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Prompt construction
    // -----------------------------------------------------------------------

    /**
     * Builds the prompt that is sent to the LLM.
     *
     * <p>The prompt provides:
     * <ol>
     *   <li>The source code of the class under test, so the LLM has authoritative
     *       knowledge of all method signatures, constructor parameters, argument
     *       types, and return types.</li>
     *   <li>The JUnit test suite, used only as an example of how the class under
     *       test is exercised - the LLM is not asked to reproduce it exactly.</li>
     *   <li>The complete Randoop parseable-sequence grammar with worked examples,
     *       using the EXACT operation IDs recognised by OperationParser.parse().</li>
     *   <li>All known limitations (no generics, no control flow, all inputs must
     *       be previously declared variables, etc.).</li>
     *   <li>The required delimiters so the response can be parsed mechanically.</li>
     * </ol>
     *
     * @param classUnderTestSource the full Java source of the class under test
     * @param testSuiteSource      the full Java source of the JUnit test suite
     * @return the prompt string
     */
    private static String buildPrompt(String classUnderTestSource, String testSuiteSource) {
        return "You are an expert in the Randoop automated test-generation tool for Java.\n"
                + "\n"
                + "## Your task\n"
                + "You are given two Java source files:\n"
                + "  1. The CLASS UNDER TEST - the authoritative source for all constructor\n"
                + "     signatures, method signatures, argument types, and return types.\n"
                + "  2. A JUNIT TEST SUITE - an example of how the class under test is\n"
                + "     exercised. Use it as inspiration only. You do NOT need to reproduce\n"
                + "     the same test cases.\n"
                + "\n"
                + "Your goal is to generate Randoop-parseable sequences that exercise the\n"
                + "class under test. Base ALL type names, method names, and constructor\n"
                + "signatures STRICTLY on what you see in the class under test source code.\n"
                + "Do not invent methods or constructors that are not present there.\n"
                + "\n"
                + "## Randoop parseable sequence format - complete specification\n"
                + "\n"
                + "Each sequence is a list of statements, one per line, in the following form:\n"
                + "\n"
                + "    VAR = OPERATION_KIND : OPERATION_DESCRIPTOR : INPUT_VAR_1 INPUT_VAR_2 ...\n"
                + "\n"
                + "Where:\n"
                + "  - VAR        is an arbitrary identifier (e.g. var0, obj1, intVal).\n"
                + "  - OPERATION_KIND is EXACTLY one of the seven IDs listed below.\n"
                + "                   Do NOT use any other string (not cons, prim, method, etc.).\n"
                + "  - OPERATION_DESCRIPTOR is the format required by that specific kind (see below).\n"
                + "  - INPUT_VARs are the names of variables declared by EARLIER lines in the\n"
                + "    same sequence. There must be NO inline expressions, NO literals as\n"
                + "    direct arguments - every argument must be a previously declared variable.\n"
                + "  - Sequences are STRAIGHT-LINE CODE ONLY. No if/for/while/try.\n"
                + "  - Do NOT include blank lines inside a sequence block.\n"
                + "\n"
                + "==========================================================================\n"
                + "### OPERATION_KIND 1: NonreceiverTerm\n"
                + "Used for: primitive values, String values, null values.\n"
                + "\n"
                + "Format of OPERATION_DESCRIPTOR:\n"
                + "    TYPE:VALUE\n"
                + "\n"
                + "Rules:\n"
                + "  - TYPE must be a fully-qualified type name (no generics).\n"
                + "  - For char: VALUE is the hex code of the character (e.g. 61 for 'a').\n"
                + "  - For String: VALUE must be enclosed in double-quotes, with standard\n"
                + "    Java escape sequences (\\n, \\r, \\t, \\\", \\\\, \\uXXXX).\n"
                + "    Use 'null' (without quotes) to represent a null String.\n"
                + "  - For any non-primitive/non-String type: VALUE must be 'null'.\n"
                + "  - NonreceiverTerm takes NO input variables (the input list is always empty).\n"
                + "\n"
                + "Examples:\n"
                + "    var0 = NonreceiverTerm : int:42 :\n"
                + "    var1 = NonreceiverTerm : long:100 :\n"
                + "    var2 = NonreceiverTerm : double:3.14 :\n"
                + "    var3 = NonreceiverTerm : float:1.5 :\n"
                + "    var4 = NonreceiverTerm : boolean:true :\n"
                + "    var5 = NonreceiverTerm : char:61 :\n"
                + "    var6 = NonreceiverTerm : java.lang.String:\"hello world\" :\n"
                + "    var7 = NonreceiverTerm : java.lang.String:null :\n"
                + "    var8 = NonreceiverTerm : java.lang.Object:null :\n"
                + "\n"
                + "==========================================================================\n"
                + "### OPERATION_KIND 2: ConstructorCall\n"
                + "Used for: calling a constructor to create an object.\n"
                + "\n"
                + "Format of OPERATION_DESCRIPTOR:\n"
                + "    FULLY.QUALIFIED.ClassName.(ArgType1,ArgType2,...)\n"
                + "\n"
                + "IMPORTANT: there is NO '<init>' keyword. The class name is followed\n"
                + "immediately by a DOT and then the opening parenthesis '('.\n"
                + "\n"
                + "Rules:\n"
                + "  - Class name MUST be fully qualified (e.g. java.util.LinkedList, NOT LinkedList).\n"
                + "  - Argument types MUST be fully qualified with NO generic parameters\n"
                + "    (use raw types, e.g. java.util.Collection not java.util.Collection<String>).\n"
                + "  - Derive exact constructor argument types from the CLASS UNDER TEST source.\n"
                + "  - For inner classes use '$' separator (e.g. OuterClass$InnerClass).\n"
                + "  - No-arg constructor: leave parentheses empty, and input list empty.\n"
                + "  - One input variable per constructor argument, in declaration order.\n"
                + "\n"
                + "Examples:\n"
                + "    var0 = ConstructorCall : java.util.LinkedList.() :\n"
                + "    var0 = ConstructorCall : java.io.StringWriter.() :\n"
                + "    var1 = ConstructorCall : java.io.PrintWriter.(java.io.Writer) : var0\n"
                + "    var2 = ConstructorCall : java.util.HashMap.(int,float) : var_capacity var_loadFactor\n"
                + "\n"
                + "==========================================================================\n"
                + "### OPERATION_KIND 3: MethodCall\n"
                + "Used for: calling an instance or static method.\n"
                + "\n"
                + "Format of OPERATION_DESCRIPTOR:\n"
                + "    FULLY.QUALIFIED.ClassName.methodName(ArgType1,ArgType2,...)\n"
                + "\n"
                + "Rules:\n"
                + "  - Class name and argument types MUST be fully qualified, NO generics.\n"
                + "  - Return type is NOT part of the descriptor.\n"
                + "  - For INSTANCE methods: the receiver variable comes FIRST in the input\n"
                + "    list, before any other arguments.\n"
                + "  - For STATIC methods: there is no receiver variable.\n"
                + "  - Void methods still need an output variable name.\n"
                + "  - Derive exact method argument types from the CLASS UNDER TEST source.\n"
                + "\n"
                + "Examples:\n"
                + "    var0 = ConstructorCall : java.util.LinkedList.() :\n"
                + "    var1 = MethodCall : java.util.LinkedList.size() : var0\n"
                + "\n"
                + "    var0 = ConstructorCall : java.util.LinkedList.() :\n"
                + "    var1 = ConstructorCall : java.lang.Object.() :\n"
                + "    var2 = MethodCall : java.util.LinkedList.add(java.lang.Object) : var0 var1\n"
                + "\n"
                + "    var0 = NonreceiverTerm : java.lang.String:\"hello\" :\n"
                + "    var1 = MethodCall : java.lang.String.valueOf(java.lang.Object) : var0\n"
                + "\n"
                + "==========================================================================\n"
                + "### OPERATION_KIND 4: InitializedArrayCreation\n"
                + "Used for: creating a one-dimensional array with explicit element values.\n"
                + "\n"
                + "Format of OPERATION_DESCRIPTOR:\n"
                + "    elementType[length]\n"
                + "\n"
                + "Rules:\n"
                + "  - elementType is the fully-qualified element type (e.g. int, java.lang.String).\n"
                + "  - length is the number of elements.\n"
                + "  - There must be exactly 'length' input variables, one per array cell,\n"
                + "    all declared earlier in the sequence.\n"
                + "  - No generic element types.\n"
                + "\n"
                + "Examples:\n"
                + "    var0 = NonreceiverTerm : int:1 :\n"
                + "    var1 = NonreceiverTerm : int:2 :\n"
                + "    var2 = NonreceiverTerm : int:3 :\n"
                + "    var3 = InitializedArrayCreation : int[3] : var0 var1 var2\n"
                + "\n"
                + "    var0 = NonreceiverTerm : java.lang.String:\"a\" :\n"
                + "    var1 = NonreceiverTerm : java.lang.String:\"b\" :\n"
                + "    var2 = InitializedArrayCreation : java.lang.String[2] : var0 var1\n"
                + "\n"
                + "==========================================================================\n"
                + "### OPERATION_KIND 5: EnumConstant\n"
                + "Used for: referencing an enum constant value.\n"
                + "\n"
                + "Format of OPERATION_DESCRIPTOR:\n"
                + "    FULLY.QUALIFIED.EnumType:CONSTANT_NAME\n"
                + "\n"
                + "Rules:\n"
                + "  - EnumType must be the fully-qualified binary name of the enum class\n"
                + "    (use '$' for inner enums, e.g. OuterClass$MyEnum).\n"
                + "  - CONSTANT_NAME must be an exact enum constant name (case-sensitive).\n"
                + "  - EnumConstant takes NO input variables (the input list is always empty).\n"
                + "\n"
                + "Examples:\n"
                + "    var0 = EnumConstant : java.time.DayOfWeek:MONDAY :\n"
                + "    var0 = EnumConstant : java.nio.file.StandardOpenOption:READ :\n"
                + "\n"
                + "==========================================================================\n"
                + "### OPERATION_KIND 6: FieldGet\n"
                + "Used for: reading the value of a public (accessible) field.\n"
                + "\n"
                + "Format of OPERATION_DESCRIPTOR:\n"
                + "    FULLY.QUALIFIED.ClassName.(fieldName)\n"
                + "\n"
                + "Rules:\n"
                + "  - The field name is enclosed in parentheses.\n"
                + "  - For INSTANCE fields: the receiver object variable is the sole input.\n"
                + "  - For STATIC fields: the input list is empty.\n"
                + "  - Only use for fields that are actually public/accessible.\n"
                + "\n"
                + "Examples:\n"
                + "    var0 = ConstructorCall : java.awt.Point.() :\n"
                + "    var1 = FieldGet : java.awt.Point.(x) : var0\n"
                + "\n"
                + "    var0 = FieldGet : java.lang.System.(out) :\n"
                + "\n"
                + "==========================================================================\n"
                + "### OPERATION_KIND 7: FieldSet\n"
                + "Used for: writing a value to a public (accessible), non-final field.\n"
                + "\n"
                + "Format of OPERATION_DESCRIPTOR:\n"
                + "    FULLY.QUALIFIED.ClassName.(fieldName)\n"
                + "\n"
                + "Rules:\n"
                + "  - The field name is enclosed in parentheses.\n"
                + "  - For INSTANCE fields: input list is [receiverVar, valueVar].\n"
                + "  - For STATIC fields: input list is [valueVar] only.\n"
                + "  - The field must not be final.\n"
                + "\n"
                + "Examples:\n"
                + "    var0 = ConstructorCall : java.awt.Point.() :\n"
                + "    var1 = NonreceiverTerm : int:10 :\n"
                + "    var2 = FieldSet : java.awt.Point.(x) : var0 var1\n"
                + "\n"
                + "==========================================================================\n"
                + "## Limitations you MUST respect\n"
                + "1.  Use ONLY these seven OPERATION_KIND values: NonreceiverTerm, ConstructorCall,\n"
                + "    MethodCall, InitializedArrayCreation, EnumConstant, FieldGet, FieldSet.\n"
                + "    Never use cons, prim, method, field, array, literal, or any other string.\n"
                + "2.  All class, method, and field names must be fully qualified - no simple names.\n"
                + "3.  No generic type parameters anywhere in descriptors.\n"
                + "4.  ConstructorCall descriptor format: ClassName.(ArgTypes) - NO '<init>'.\n"
                + "5.  Every input to a ConstructorCall or MethodCall must be a variable declared\n"
                + "    earlier in the SAME sequence. Never pass a literal directly - declare it\n"
                + "    first with NonreceiverTerm.\n"
                + "6.  Do not include JUnit assertions (assertEquals, assertTrue, etc.).\n"
                + "7.  Do not include calls to private or helper methods of the test class.\n"
                + "8.  Do not include control flow (if, for, while, try/catch).\n"
                + "9.  Only call methods and constructors that actually exist in the class\n"
                + "    under test source code provided below.\n"
                + "10. If a call cannot be expressed as a single straight-line Randoop\n"
                + "    statement, SKIP it or replace it with a simpler equivalent.\n"
                + "11. Prefer short sequences (3-8 statements). It is better to produce\n"
                + "    several short valid sequences than one long invalid one.\n"
                + "12. Each sequence must be self-contained: every variable it uses must be\n"
                + "    declared within the same sequence.\n"
                + "13. Do NOT include blank lines inside a sequence block.\n"
                + "\n"
                + "## Output format\n"
                + "For EACH sequence, output it enclosed between the exact delimiters below\n"
                + "(one sequence per block, no other text inside the block):\n"
                + "\n"
                + "===SEQ_START===\n"
                + "var0 = ...\n"
                + "var1 = ...\n"
                + "===SEQ_END===\n"
                + "\n"
                + "You may output as many sequence blocks as you think are useful.\n"
                + "Do not include any explanation inside the delimiters - only the sequence lines.\n"
                + "You MAY include brief comments outside the delimiters to explain your choices.\n"
                + "\n"
                + "## Class under test (authoritative source for all signatures)\n"
                + "```java\n"
                + classUnderTestSource + "\n"
                + "```\n"
                + "\n"
                + "## JUnit test suite (inspiration only - do not reproduce it exactly)\n"
                + "```java\n"
                + testSuiteSource + "\n"
                + "```\n";
    }

    // -----------------------------------------------------------------------
// LLM API calls — JDK 1.8 compatible
// -----------------------------------------------------------------------

    /**
     * Calls the Anthropic Claude API with the given prompt and returns the
     * text content of the first response message.
     *
     * <p>Reads the API key from the {@code ANTHROPIC_API_KEY} environment
     * variable.
     *
     * @param prompt the user prompt
     * @return the text of the model's reply
     * @throws IOException on HTTP or I/O errors
     */
    private static String callClaude(String prompt) throws IOException {
        String apiKey = requireEnvVar("ANTHROPIC_API_KEY");

        // Build JSON request body using plain concatenation (JDK 1.8 compatible).
        String requestBody = "{"
                + "\"model\":\"" + ANTHROPIC_MODEL + "\","
                + "\"max_tokens\":" + MAX_TOKENS + ","
                + "\"messages\":["
                +   "{"
                +     "\"role\":\"user\","
                +     "\"content\":" + jsonString(prompt)
                +   "}"
                + "]"
                + "}";

        java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Content-Type",      "application/json");
        headers.put("x-api-key",         apiKey);
        headers.put("anthropic-version", ANTHROPIC_VERSION);

        String responseBody = sendRequest(ANTHROPIC_API_URL, "POST", requestBody, headers);
        return extractClaudeText(responseBody);
    }

    /**
     * Calls the OpenAI GPT API with the given prompt and returns the text
     * content of the first choice message.
     *
     * <p>Reads the API key from the {@code OPENAI_API_KEY} environment variable.
     *
     * @param prompt the user prompt
     * @return the text of the model's reply
     * @throws IOException on HTTP or I/O errors
     */
    private static String callGpt(String prompt) throws IOException {
        String apiKey = requireEnvVar("OPENAI_API_KEY");

        // Build JSON request body using plain concatenation (JDK 1.8 compatible).
        String requestBody = "{"
                + "\"model\":\"" + OPENAI_MODEL + "\","
//                + "\"max_tokens\":" + MAX_TOKENS + ","
                + "\"messages\":["
                +   "{"
                +     "\"role\":\"system\","
                +     "\"content\":\"You are an expert in the Randoop Java test generation tool.\""
                +   "},"
                +   "{"
                +     "\"role\":\"user\","
                +     "\"content\":" + jsonString(prompt)
                +   "}"
                + "]"
                + "}";

        java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Content-Type",  "application/json");
        headers.put("Authorization", "Bearer " + apiKey);

        String responseBody = sendRequest(OPENAI_API_URL, "POST", requestBody, headers);
        return extractGptText(responseBody);
    }

    /**
     * Sends an HTTP POST request using {@link java.net.HttpURLConnection}
     * (JDK 1.8 compatible replacement for {@code java.net.http.HttpClient}).
     * Returns the response body as a string. Throws {@link IOException} if
     * the HTTP status code indicates an error.
     *
     * @param url     the endpoint URL
     * @param method  the HTTP method (e.g. "POST")
     * @param body    the request body as a UTF-8 string
     * @param headers the HTTP request headers
     * @return the response body as a string
     * @throws IOException if the status code is not 2xx or on any I/O error
     */
    private static String sendRequest(String url,
                                      String method,
                                      String body,
                                      java.util.Map<String, String> headers)
            throws IOException {

        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();

        try {
            conn.setRequestMethod(method);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(120000); // 120 seconds
            conn.setReadTimeout(300000);   // 300 seconds (LLMs can be slow)

            // Set request headers.
            for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            // Write request body.
            byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            java.io.OutputStream os = conn.getOutputStream();
            try {
                os.write(bodyBytes);
                os.flush();
            } finally {
                os.close();
            }

            // Read response.
            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String responseBody = readStream(is);

            if (status < 200 || status >= 300) {
                throw new IOException(
                        "LLM API returned HTTP " + status + ": " + responseBody);
            }

            return responseBody;

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Reads an {@link java.io.InputStream} fully and returns its content as
     * a UTF-8 string. The stream is closed after reading.
     *
     * @param is the input stream to read
     * @return the stream content as a string
     * @throws IOException on any I/O error
     */
    private static String readStream(java.io.InputStream is) throws IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
    // -----------------------------------------------------------------------
    // JSON response parsing  (minimal, no external library required)
    // -----------------------------------------------------------------------

    /**
     * Extracts the text content from a Claude API JSON response.
     *
     * <p>Claude returns:
     * <pre>
     * { "content": [ { "type": "text", "text": "..." } ], ... }
     * </pre>
     *
     * @param json the raw JSON response body
     * @return the extracted text
     * @throws IOException if the text field cannot be found
     */
    private static String extractClaudeText(String json) throws IOException {
        // Extract the value of the first "text" key in the content array.
        String marker = "\"text\":";
        int idx = json.indexOf(marker);
        if (idx == -1) {
            throw new IOException(
                    "Could not find 'text' field in Claude response: " + json);
        }
        return extractJsonStringValue(json, idx + marker.length());
    }

    /**
     * Extracts the text content from an OpenAI GPT API JSON response.
     *
     * <p>GPT returns:
     * <pre>
     * { "choices": [ { "message": { "content": "..." } } ], ... }
     * </pre>
     *
     * @param json the raw JSON response body
     * @return the extracted text
     * @throws IOException if the content field cannot be found
     */
    private static String extractGptText(String json) throws IOException {
        String marker = "\"content\":";
        int idx = json.indexOf(marker);
        if (idx == -1) {
            throw new IOException(
                    "Could not find 'content' field in GPT response: " + json);
        }
        return extractJsonStringValue(json, idx + marker.length());
    }

    /**
     * Extracts a JSON string value starting at {@code fromIndex} in
     * {@code json}. Handles standard JSON escape sequences ({@code \n},
     * {@code \r}, {@code \t}, {@code \\}, {@code \"}, {@code XXXX}).
     *
     * @param json      the JSON text
     * @param fromIndex the position at which to start searching for the
     *                  opening double-quote
     * @return the unescaped string value
     * @throws IOException if no valid string is found
     */
    private static String extractJsonStringValue(String json, int fromIndex)
            throws IOException {

        int start = json.indexOf('"', fromIndex);
        if (start == -1) {
            throw new IOException("No opening quote found at index " + fromIndex);
        }

        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') {
                    sb.append('"');
                    i += 2;
                } else if (next == '\\') {
                    sb.append('\\');
                    i += 2;
                } else if (next == '/') {
                    sb.append('/');
                    i += 2;
                } else if (next == 'n') {
                    sb.append('\n');
                    i += 2;
                } else if (next == 'r') {
                    sb.append('\r');
                    i += 2;
                } else if (next == 't') {
                    sb.append('\t');
                    i += 2;
                } else if (next == 'b') {
                    sb.append('\b');
                    i += 2;
                } else if (next == 'f') {
                    sb.append('\f');
                    i += 2;
                } else if (next == 'u') {
                    if (i + 5 < json.length()) {
                        String hex = json.substring(i + 2, i + 6);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 6;
                    } else {
                        sb.append(next);
                        i += 2;
                    }
                } else {
                    sb.append(next);
                    i += 2;
                }
            } else if (c == '"') {
                // Closing quote — done.
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        throw new IOException("Unterminated JSON string starting at " + start);
    }

    // -----------------------------------------------------------------------
    // Sequence block extraction and line parsing
    // -----------------------------------------------------------------------

    /**
     * Scans {@code rawReply} for all text blocks delimited by
     * {@code ===SEQ_START===} and {@code ===SEQ_END===} and returns them as
     * a list of raw block strings (delimiters not included).
     *
     * @param rawReply the full LLM response text
     * @return list of raw sequence block strings
     */
    private static List<String> extractSequenceBlocks(String rawReply) {
        List<String> blocks = new ArrayList<String>();
        int searchFrom = 0;
        while (true) {
            int start = rawReply.indexOf(SEQ_START, searchFrom);
            if (start == -1) break;
            int contentStart = start + SEQ_START.length();
            int end = rawReply.indexOf(SEQ_END, contentStart);
            String block;
            if (end == -1) {
                // Unterminated block — take everything to end of string.
                block = rawReply.substring(contentStart).trim();
                if (!block.isEmpty()) {
                    blocks.add(block);
                }
                break;
            }
            block = rawReply.substring(contentStart, end).trim();
            if (!block.isEmpty()) {  // skip empty blocks
                blocks.add(block);
            }
            searchFrom = end + SEQ_END.length();
        }
        return blocks;
    }

    /**
     * Splits a raw sequence block into individual non-empty statement lines,
     * stripping leading/trailing whitespace and skipping comment lines
     * (lines starting with {@code //} or {@code #}).
     *
     * @param block the raw block text
     * @return the list of statement lines
     */
    private static List<String> parseBlockIntoLines(String block) {
        // \R is supported in java.util.regex since JDK 8.
        // String.strip() (JDK 11) replaced with String.trim() (JDK 1.8).
        List<String> lines = new ArrayList<String>();
        for (String raw : block.split("\\R")) {
            String line = raw.trim(); // was: raw.strip()
            if (line.isEmpty()) continue;
            if (line.startsWith("//") || line.startsWith("#")) continue;
            lines.add(line);
        }
        return lines;
    }

    // -----------------------------------------------------------------------
    // Miscellaneous utilities
    // -----------------------------------------------------------------------

    /**
     * Returns the value of the named environment variable, or throws a clear
     * {@link IllegalStateException} if it is not set.
     *
     * @param name the environment variable name
     * @return its value
     */
    private static String requireEnvVar(String name) {
        String value = System.getenv(name);
        if (value == null || StringUtils.isBlank(value)){ //value.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable '" + name + "' is not set. "
                            + "Please export it before running TestSuiteReader.");
        }
        return value;
    }

    /**
     * Encodes a Java string as a JSON string literal (including the surrounding
     * double-quotes). Escapes characters that are special in JSON.
     *
     * @param s the raw Java string
     * @return the JSON-encoded string literal
     */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c < 0x20) {
                // Control characters must be Unicode-escaped in JSON.
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
//```
//
//        ---
//
//        ## Design decisions worth noting
//
//### Prompt engineering choices
//
//| Choice | Reason |
//        |---|---|
//        | Explicit `===SEQ_START===` / `===SEQ_END===` delimiters | Machine-parseable without a JSON parser; the LLM will not accidentally corrupt them |
//        | "Prefer simpler sequences" instruction | Avoids the LLM trying to faithfully reproduce complex things (chained calls, generics, `try` blocks) that Randoop cannot parse [1][2][3] |
//        | Full grammar specification in the prompt, with examples | LLMs make fewer format errors when shown exact examples rather than abstract rules |
//        | "Declare with `prim` before passing as argument" rule | Directly addresses the root constraint from Randoop's `Sequence.parse()` that all inputs must be previously declared variables |
//        | Instruction to skip private helpers and assertions | Prevents recurrence of the `HttpParserTest.parse(String)` and `parseShouldFail(String)` mistakes seen in [1] |
//        | Short sequences (3–8 statements) preferred | Shorter sequences are less likely to fail mid-parse and are more useful as Randoop seeds |
//
//        ### No external JSON library
//Both the request body and the response parsing are handled with string operations and a minimal hand-written JSON string extractor, so no Jackson/Gson dependency is needed beyond Randoop itself.
//
//### Java 17+ features used
//- `switch` expressions with `->` (pattern switch on the provider enum)
//        - Text blocks (`"""..."""`)
//- `String.formatted()`
//        - `\R` regex line terminator
//
//If you need Java 11 compatibility, the `switch` on the enum can be replaced with an `if/else` block and the text blocks with regular string concatenation.