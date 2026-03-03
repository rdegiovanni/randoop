package randoop.generation;

import randoop.sequence.Sequence;
import randoop.sequence.SequenceParseException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(LLMBasedTestSuiteReader.class.getName());

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

    public static List<Sequence> readSequencesFromFile(Path pathToFile) {
        List<Sequence> sequences = new ArrayList<>();
        if (pathToFile != null) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(pathToFile.toFile()));
                String line;
                while ((line = br.readLine()) != null) {
                    List<Sequence> suite_sequences = LLMBasedTestSuiteReader.readSequencesFromFile(line,LlmProvider.GPT);
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

    /**
     * Reads the JUnit test suite at {@code pathToFile}, sends it to the
     * specified LLM, and returns the list of Randoop {@link Sequence} objects
     * that the LLM generated.
     *
     * <p>The LLM is prompted to generate sequences that are simpler than the
     * original tests so that they are reliably parseable by Randoop.
     * Sequences that still fail to parse after the LLM response are skipped
     * with a warning logged.
     *
     * @param filename path to a Java source file containing JUnit test methods
     * @param provider   the LLM provider ({@code CLAUDE} or {@code GPT})
     * @return a (possibly empty) list of Randoop sequences
     * @throws IOException          if the file cannot be read or the HTTP call fails
     * @throws InterruptedException if the HTTP call is interrupted
     */
    public static List<Sequence> readSequencesFromFile(String filename,
                                                       LlmProvider provider)
            throws IOException {

        // JDK 11+: Files.readString(pathToFile)
        // JDK 1.8 replacement:
        Path pathToFile = Paths.get(filename);
        String testSource = new String(
                java.nio.file.Files.readAllBytes(pathToFile),
                java.nio.charset.StandardCharsets.UTF_8);

        String prompt  = buildPrompt(testSource);
        String rawReply;

        // JDK 14+: switch expression on enum
        // JDK 1.8 replacement:
        if (provider == LlmProvider.CLAUDE) {
            rawReply = callClaude(prompt);
        } else if (provider == LlmProvider.GPT) {
            rawReply = callGpt(prompt);
        } else {
            throw new IllegalArgumentException("Unknown LLM provider: " + provider);
        }

        LOG.fine("Raw LLM reply:\n" + rawReply);

        List<String> blocks   = extractSequenceBlocks(rawReply);
        List<Sequence> result = new ArrayList<Sequence>();

        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i).trim();
            if (block.isEmpty()) continue;

            List<String> lines = parseBlockIntoLines(block);
            if (lines.isEmpty()) continue;

            try {
                Sequence seq = Sequence.parse(lines);
                result.add(seq);
                LOG.fine("Sequence " + i + " parsed successfully ("
                        + seq.size() + " statement(s)).");
            } catch (SequenceParseException | Error e) {
                LOG.log(Level.WARNING,
                        "Sequence " + i + " failed to parse, skipping: "
                                + e.getMessage());
            }
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Prompt construction
    // -----------------------------------------------------------------------

    /**
     * Builds the prompt that is sent to the LLM.
     *
     * <p>The prompt explains:
     * <ol>
     *   <li>The exact Randoop parseable-sequence grammar.</li>
     *   <li>Every operation kind with worked examples.</li>
     *   <li>All known limitations (no generics, no control flow, all inputs
     *       must be previously declared variables, etc.).</li>
     *   <li>The instruction to simplify rather than faithfully translate.</li>
     *   <li>The required {@code ===SEQ_START===} / {@code ===SEQ_END===}
     *       delimiters so the response can be parsed mechanically.</li>
     * </ol>
     *
     * @param testSource the full Java source of the JUnit test class
     * @return the prompt string
     */
    private static String buildPrompt(String testSource) {
        return "You are an expert in the Randoop automated test-generation tool for Java.\n"
                + "\n"
                + "## Your task\n"
                + "Analyse the JUnit test class below and generate a list of Randoop-parseable\n"
                + "sequences that can be used as generation seeds. The sequences do NOT need to\n"
                + "reproduce every detail of the original tests. Prefer simpler sequences that\n"
                + "are more likely to parse correctly.\n"
                + "\n"
                + "## Randoop parseable sequence format — complete specification\n"
                + "\n"
                + "Each sequence is a list of statements, one per line, in the following form:\n"
                + "\n"
                + "    VAR = OPERATION_KIND : OPERATION_DESCRIPTOR : INPUT_VAR_1 INPUT_VAR_2 ...\n"
                + "\n"
                + "Rules:\n"
                + "  - VAR is an arbitrary identifier (e.g. var0, obj1, intVal).\n"
                + "  - OPERATION_KIND is exactly one of: cons, method, prim.\n"
                + "  - INPUT_VARs are the names of variables declared by EARLIER lines in the\n"
                + "    same sequence. There must be NO inline expressions, NO literals as\n"
                + "    direct arguments to cons/method calls - every argument must be a\n"
                + "    previously declared variable.\n"
                + "  - Sequences are STRAIGHT-LINE CODE ONLY. No if/for/while/try.\n"
                + "\n"
                + "### Kind: prim - primitive or String initialisation\n"
                + "Format:\n"
                + "    VAR = prim : TYPE:VALUE :\n"
                + "\n"
                + "Supported types and value formats:\n"
                + "    int                VAR = prim : int:42 :\n"
                + "    long               VAR = prim : long:100 :\n"
                + "    double             VAR = prim : double:3.14 :\n"
                + "    float              VAR = prim : float:1.5 :\n"
                + "    boolean            VAR = prim : boolean:true :\n"
                + "    char               VAR = prim : char:'a' :\n"
                + "    java.lang.String   VAR = prim : java.lang.String:\"hello world\" :\n"
                + "\n"
                + "String values must be enclosed in double-quotes. Escape sequences allowed:\n"
                + "    \\\\ for backslash, \\\" for double-quote, \\n for newline,\n"
                + "    \\r for carriage return, \\t for tab.\n"
                + "\n"
                + "### Kind: cons - constructor call\n"
                + "Format:\n"
                + "    VAR = cons : FULLY.QUALIFIED.ClassName.<init>(ArgType1,ArgType2,...) : inputVar1 inputVar2 ...\n"
                + "\n"
                + "Rules:\n"
                + "  - Class name MUST be fully qualified (e.g. java.util.LinkedList, NOT LinkedList).\n"
                + "  - Argument types MUST be fully qualified and must NOT contain generic\n"
                + "    parameters (use raw types, e.g. java.util.Collection not\n"
                + "    java.util.Collection<java.lang.String>).\n"
                + "  - No-arg constructor example:\n"
                + "        var0 = cons : java.util.LinkedList.<init>() :\n"
                + "  - One-arg constructor example:\n"
                + "        var0 = cons : java.io.StringWriter.<init>() :\n"
                + "        var1 = cons : java.io.PrintWriter.<init>(java.io.Writer) : var0\n"
                + "\n"
                + "### Kind: method - method call\n"
                + "Format:\n"
                + "    VAR = method : FULLY.QUALIFIED.ClassName.methodName(ArgType1,...) : receiverVar inputVar1 ...\n"
                + "\n"
                + "Rules:\n"
                + "  - For INSTANCE methods the receiver variable comes FIRST, before any\n"
                + "    other arguments.\n"
                + "  - For STATIC methods there is no receiver variable.\n"
                + "  - Return type is NOT part of the descriptor.\n"
                + "  - Void methods still need an output variable name.\n"
                + "  - Fully qualified class names and raw types, as for cons.\n"
                + "  - Example (instance, no args):\n"
                + "        var0 = cons : java.util.LinkedList.<init>() :\n"
                + "        var1 = method : java.util.LinkedList.size() : var0\n"
                + "  - Example (instance, one arg):\n"
                + "        var0 = cons : java.util.LinkedList.<init>() :\n"
                + "        var1 = cons : java.lang.Object.<init>() :\n"
                + "        var2 = method : java.util.LinkedList.add(java.lang.Object) : var0 var1\n"
                + "  - Example (static):\n"
                + "        var0 = prim : java.lang.String:\"hello\" :\n"
                + "        var1 = method : java.lang.String.valueOf(java.lang.Object) : var0\n"
                + "\n"
                + "## Limitations you MUST respect\n"
                + "1. All class and method names must be fully qualified - no simple names.\n"
                + "2. No generic type parameters anywhere in descriptors.\n"
                + "3. Every input to a cons or method call must be a variable declared earlier\n"
                + "   in the SAME sequence. Never pass a literal directly to a constructor or\n"
                + "   method - declare it first with prim.\n"
                + "4. Do not include JUnit assertions (assertEquals, assertTrue, etc.).\n"
                + "5. Do not include calls to private helper methods of the test class.\n"
                + "6. Do not include control flow (if, for, while, try/catch).\n"
                + "7. If a statement from the original test cannot be expressed as a single\n"
                + "   straight-line Randoop statement, SKIP it or replace it with a simpler\n"
                + "   equivalent that CAN be expressed.\n"
                + "8. Prefer short sequences (3-8 statements). It is better to produce several\n"
                + "   short valid sequences than one long invalid one.\n"
                + "9. Each sequence must be self-contained: every variable it uses must be\n"
                + "   declared within the same sequence.\n"
                + "\n"
                + "## Output format\n"
                + "For EACH sequence, output it enclosed between the exact delimiters below\n"
                + "(one sequence per block, no other text inside the block):\n"
                + "\n"
                + "===SEQ_START===\n"
                + "var0 = ...\n"
                + "var1 = ...\n"
                + "...\n"
                + "===SEQ_END===\n"
                + "\n"
                + "You may output as many sequence blocks as you think are useful.\n"
                + "Do not include any explanation inside the delimiters - only the sequence lines.\n"
                + "You MAY include brief comments outside the delimiters to explain your choices.\n"
                + "\n"
                + "## JUnit test class to analyse\n"
                + "```java\n"
                + testSource + "\n"
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
        List<String> blocks = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int start = rawReply.indexOf(SEQ_START, searchFrom);
            if (start == -1) break;
            int contentStart = start + SEQ_START.length();
            int end = rawReply.indexOf(SEQ_END, contentStart);
            if (end == -1) {
                // Unterminated block — take everything to the end of the string.
                blocks.add(rawReply.substring(contentStart).trim());
                break;
            }
            blocks.add(rawReply.substring(contentStart, end).trim());
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
        if (value == null || value.isBlank()) {
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