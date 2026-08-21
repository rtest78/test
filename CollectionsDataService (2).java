package com.example.collections.service;

import com.example.collections.model.AccountDayView;
import com.example.collections.model.AccountDayView.ChangeType;
import com.example.collections.model.AccountRecord;
import com.example.collections.model.ComparisonSummary;
import com.example.collections.model.ChannelCount;
import com.example.collections.model.DailySnapshot;
import com.example.collections.model.DailySummary;
import com.example.collections.model.FieldChange;
import com.example.collections.model.TransitionCount;
import com.example.collections.model.ProcessingAnomaly;
import com.example.collections.model.VerificationSummary;
import com.example.collections.model.HistoricalReport;
import com.example.collections.model.ChannelAmount;
import com.example.collections.model.CollectedAccountView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CollectionsDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionsDataService.class);

    private static final List<String> MEMBER_ALIASES = Arrays.asList(
            "membernumber", "membernum", "memberid", "memberidentifier", "customernumber", "customerid");
    private static final List<String> ACCOUNT_ALIASES = Arrays.asList(
            "accountnumber", "accountnum", "accountid", "accountidentifier", "applicationnumber", "applicationid", "loannumber");
    private static final List<String> REQUEST_SECTIONS = Arrays.asList("input", "request", "requestdata", "decisionrequest");
    private static final List<String> RESPONSE_SECTIONS = Arrays.asList("output", "response", "responsedata", "decisionresponse");
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})[-_]?([01]\\d)[-_]?([0-3]\\d)");
    private static final Pattern US_DATE = Pattern.compile("([01]\\d)[-_]?([0-3]\\d)[-_]?(20\\d{2})");

    private final ObjectMapper mapper;
    private final Path dataDirectory;
    private volatile Map<String, DailySnapshot> snapshots = Collections.emptyMap();
    private volatile long lastScanTimeMs;
    private volatile String lastLoadError = "";

    public CollectionsDataService(ObjectMapper mapper,
            @Value("${collections.data.directory:./data}") String dataDirectory) {
        this.mapper = mapper;
        this.dataDirectory = resolveDataDirectory(dataDirectory);
    }

    /**
     * A relative folder such as ./data is normally resolved from the JVM working
     * directory. IDEs and executable JARs can use a different working directory,
     * so also check locations relative to the compiled classes/JAR and project
     * directory. A candidate that actually contains a supported file wins.
     */
    private Path resolveDataDirectory(String configuredDirectory) {
        Path configuredPath = Paths.get(configuredDirectory).normalize();
        if (configuredPath.isAbsolute()) return configuredPath.toAbsolutePath().normalize();

        Set<Path> candidates = new LinkedHashSet<Path>();
        Path workingDirectory = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        addDirectoryCandidates(candidates, workingDirectory, configuredPath);

        try {
            URI codeSource = CollectionsDataService.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codeLocation = Paths.get(codeSource).toAbsolutePath().normalize();
            if (Files.isRegularFile(codeLocation)) codeLocation = codeLocation.getParent();
            addDirectoryCandidates(candidates, codeLocation, configuredPath);
        } catch (Exception ex) {
            LOGGER.debug("Unable to resolve the application code location while locating the data folder.", ex);
        }

        for (Path candidate : candidates) {
            if (containsInputFile(candidate)) return candidate;
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        return workingDirectory.resolve(configuredPath).toAbsolutePath().normalize();
    }

    private void addDirectoryCandidates(Set<Path> candidates, Path start, Path configuredPath) {
        Path current = start;
        for (int level = 0; current != null && level < 6; level++) {
            candidates.add(current.resolve(configuredPath).toAbsolutePath().normalize());
            candidates.add(current.resolve("src/main/resources").resolve(configuredPath).toAbsolutePath().normalize());
            current = current.getParent();
        }
    }

    private boolean containsInputFile(Path candidate) {
        if (!Files.isDirectory(candidate) || !Files.isReadable(candidate)) return false;
        try (Stream<Path> stream = Files.walk(candidate)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (IOException ex) {
            return false;
        }
    }

    @PostConstruct
    public void initialize() throws IOException {
        LOGGER.info("Initializing Collections file loader. Configured folder: [{}]", dataDirectory);
        reload();
        LOGGER.info("Completed Collections file-loader initialization. Dates loaded: [{}]", snapshots.size());
    }

    public synchronized void reload() throws IOException {
        Map<String, DailySnapshot> loadedSnapshots = new TreeMap<String, DailySnapshot>();
        try {
            LOGGER.info("Scanning Collections input folder recursively: [{}]", dataDirectory);
            if (!Files.exists(dataDirectory)) Files.createDirectories(dataDirectory);
            if (!Files.isDirectory(dataDirectory)) {
                throw new IOException("Configured collections data path is not a directory: " + dataDirectory);
            }
            if (!Files.isReadable(dataDirectory)) {
                throw new IOException("Configured collections data directory is not readable: " + dataDirectory);
            }

            List<Path> files;
            try (Stream<Path> stream = Files.walk(dataDirectory)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .sorted()
                        .collect(Collectors.toList());
            }

            if (files.isEmpty()) {
                LOGGER.warn("No Collections input files found in [{}]. File extensions are not required. "
                                + "Place the files in this folder or set collections.data.directory to the correct folder.",
                        dataDirectory);
            } else {
                LOGGER.info("Found [{}] supported Collections input file(s).", files.size());
            }

            for (Path file : files) loadFile(file, loadedSnapshots);
            snapshots = Collections.unmodifiableMap(loadedSnapshots);
            lastLoadError = "";

            long totalAccounts = 0;
            long totalSourceRecords = 0;
            long totalParsedRecords = 0;
            long totalRejectedRecords = 0;
            for (DailySnapshot snapshot : loadedSnapshots.values()) {
                totalAccounts += snapshot.getAccountCount();
                totalSourceRecords += snapshot.getTotalLines();
                totalParsedRecords += snapshot.getParsedLines();
                totalRejectedRecords += snapshot.getRejectedLines();
            }
            LOGGER.info("Collections folder scan completed. Files: [{}], dates: [{}], accounts: [{}], "
                            + "source records: [{}], parsed records: [{}], rejected records: [{}].",
                    files.size(), loadedSnapshots.size(), totalAccounts, totalSourceRecords,
                    totalParsedRecords, totalRejectedRecords);
        } catch (IOException ex) {
            lastLoadError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            LOGGER.error("Collections folder scan failed for [{}]: {}", dataDirectory, lastLoadError, ex);
            throw ex;
        } finally {
            lastScanTimeMs = System.currentTimeMillis();
        }
    }

    private void loadFile(Path file, Map<String, DailySnapshot> loadedSnapshots) throws IOException {
        String relativeFile = dataDirectory.relativize(file).toString();
        LOGGER.info("Loading Collections file [{}]. Business date will be read from each row.", relativeFile);

        long rowNumber = 0;
        Set<String> datesInFile = new LinkedHashSet<String>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.trim().isEmpty()) continue;

                String businessDate = businessDateFromRow(line, file.getFileName().toString());
                DailySnapshot snapshot = loadedSnapshots.get(businessDate);
                if (snapshot == null) {
                    snapshot = new DailySnapshot(businessDate);
                    loadedSnapshots.put(businessDate, snapshot);
                }
                snapshot.addSourceFile(relativeFile);
                datesInFile.add(businessDate);

                try {
                    JsonNode requestResponsePair = parseCollectionsRow(line);
                    processSourceRecord(requestResponsePair, file, snapshot, rowNumber);
                } catch (Exception ex) {
                    snapshot.incrementTotalLines();
                    snapshot.incrementRejectedLines();
                    LOGGER.warn("Rejected row [{}] from Collections file [{}]: {}",
                            rowNumber, relativeFile, ex.getMessage());
                }
            }
        }

        LOGGER.info("Finished Collections file [{}]. Rows read: [{}], business dates: [{}].",
                relativeFile, rowNumber, datesInFile);
    }

    /**
     * The actual feed row is:
     *
     * business-date,{"input":{...}},{"output":{...}}
     *
     * The physical file is therefore CSV-like text containing two adjacent JSON
     * objects; the complete line is not itself JSON. Parse both objects and build
     * one outer object so input.data is always the request and output.data is
     * always the response for the same execution.
     */
    private JsonNode parseCollectionsRow(String line) throws IOException {
        int firstComma = line.indexOf(',');
        String payload = firstComma < 0 ? line : line.substring(firstComma + 1);
        List<JsonNode> jsonObjects = extractRootJsonObjects(payload);
        if (jsonObjects.isEmpty()) {
            throw new IOException("No request or response JSON object was found");
        }

        ObjectNode pair = mapper.createObjectNode();
        for (JsonNode object : jsonObjects) {
            if (!object.isObject()) continue;
            JsonNode input = directField(object, "input");
            JsonNode output = directField(object, "output");
            if (input != null) pair.set("input", input);
            if (output != null) pair.set("output", output);
        }

        if (pair.has("input") || pair.has("output")) return pair;
        if (jsonObjects.size() == 1) return jsonObjects.get(0);
        throw new IOException("JSON objects were found, but neither input nor output was present");
    }

    private List<JsonNode> extractRootJsonObjects(String text) throws IOException {
        List<JsonNode> result = new ArrayList<JsonNode>();
        StringBuilder document = new StringBuilder(64 * 1024);
        boolean started = false;
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (!started) {
                if (character != '{' && character != '[') continue;
                document.setLength(0);
                document.append(character);
                started = true;
                inString = false;
                escaped = false;
                depth = 1;
                continue;
            }

            document.append(character);
            if (inString) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }

            if (character == '"') inString = true;
            else if (character == '{' || character == '[') depth++;
            else if (character == '}' || character == ']') {
                depth--;
                if (depth == 0) {
                    result.add(mapper.readTree(document.toString()));
                    document.setLength(0);
                    started = false;
                }
            }
        }

        if (started) throw new IOException("Incomplete JSON object at the end of the row");
        return result;
    }

    private JsonNode directField(JsonNode object, String expectedName) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (expectedName.equals(canonical(field.getKey()))) return field.getValue();
        }
        return null;
    }

    private String businessDateFromRow(String line, String fileName) {
        int comma = line.indexOf(',');
        if (comma > 0) {
            String dateText = line.substring(0, comma).replace("\uFEFF", "").trim();
            String parsed = validDate(dateText);
            if (!"UNDATED".equals(parsed)) return parsed;
        }
        return businessDate(fileName);
    }

    private void processSourceRecord(JsonNode node, Path file, DailySnapshot snapshot, long index) {
        snapshot.incrementTotalLines();
        try { processParsedRecord(node, file, snapshot, index); }
        catch (Exception ex) {
            snapshot.incrementRejectedLines();
            LOGGER.warn("Rejected source record [{}] from file [{}]: {}", index, file, ex.getMessage());
        }
    }

    private void processParsedRecord(JsonNode outer, Path file, DailySnapshot snapshot, long sourceLine) throws IOException {
        List<AccountRecord> records = parseOuterRecord(outer, snapshot.getBusinessDate(), file.getFileName().toString(), sourceLine);
        if (records.isEmpty()) {
            snapshot.incrementRejectedLines();
            LOGGER.warn("No account/application elements were found in source record [{}] from file [{}].",
                    sourceLine, file);
            return;
        }
        for (AccountRecord record : records) addOrMergeRequestResponse(snapshot, record);
        snapshot.incrementParsedLines();
    }

    /**
     * Request and response entries can be separate root objects in the same
     * physical file. Keep the request fields from input.data and add the response
     * fields from output.data to the same member/account instead of replacing the
     * first entry or counting the matching response as a duplicate application.
     */
    private void addOrMergeRequestResponse(DailySnapshot snapshot, AccountRecord incoming) {
        AccountRecord existing = findExistingAccount(snapshot, incoming);
        if (existing == null) {
            snapshot.add(incoming);
            return;
        }

        boolean requestCollision = !existing.getRequestFields().isEmpty()
                && !incoming.getRequestFields().isEmpty();
        boolean responseCollision = !existing.getResponseFields().isEmpty()
                && !incoming.getResponseFields().isEmpty();
        boolean addsMissingRequest = existing.getRequestFields().isEmpty()
                && !incoming.getRequestFields().isEmpty();
        boolean addsMissingResponse = existing.getResponseFields().isEmpty()
                && !incoming.getResponseFields().isEmpty();

        if (!requestCollision && !responseCollision
                && (addsMissingRequest || addsMissingResponse)) {
            Map<String, String> requestFields = new LinkedHashMap<String, String>();
            requestFields.putAll(existing.getRequestFields());
            requestFields.putAll(incoming.getRequestFields());

            Map<String, String> responseFields = new LinkedHashMap<String, String>();
            responseFields.putAll(existing.getResponseFields());
            responseFields.putAll(incoming.getResponseFields());

            existing.setRequestFields(requestFields);
            existing.setResponseFields(responseFields);
            return;
        }

        // Two requests or two responses for the same member/account are genuine
        // duplicates and should continue through DailySnapshot.add for reporting.
        snapshot.add(incoming);
    }

    private AccountRecord findExistingAccount(DailySnapshot snapshot, AccountRecord incoming) {
        Map<String, AccountRecord> memberAccounts = snapshot.getAccountsByMember().get(incoming.getMemberNumber());
        if (memberAccounts != null) {
            AccountRecord direct = memberAccounts.get(incoming.getAccountNumber());
            if (direct != null) return direct;
        }

        // Some output.data variants omit memberNumber but retain accountNumber.
        // Match by account only when it is unique for the business date.
        if (!"UNKNOWN_MEMBER".equals(incoming.getMemberNumber())) return null;
        AccountRecord unique = null;
        for (Map<String, AccountRecord> accounts : snapshot.getAccountsByMember().values()) {
            AccountRecord candidate = accounts.get(incoming.getAccountNumber());
            if (candidate == null) continue;
            if (unique != null) return null;
            unique = candidate;
        }
        return unique;
    }

    public List<AccountRecord> parseOuterRecord(JsonNode outer, String businessDate, String sourceFile, long sourceLine) throws IOException {
        JsonNode decoded = decodeEmbedded(outer, 0);

        // The original Collections extract contract stores the request and
        // response as escaped JSON in input.data and output.data respectively.
        // Resolve those exact locations first so a nested field named request or
        // response cannot be selected accidentally.
        JsonNode requestRoot = findExactDataSection(decoded, "input");
        JsonNode responseRoot = findExactDataSection(decoded, "output");

        // Retain the earlier aliases only when this is not an input/output record.
        // This prevents a nested field inside output.data from being mistaken for
        // the request (and the reverse for input.data).
        if (requestRoot == null && responseRoot == null) {
            requestRoot = findSection(decoded, REQUEST_SECTIONS);
            responseRoot = findSection(decoded, RESPONSE_SECTIONS);
        }

        // Only use the complete object as a legacy request when neither an input
        // nor an output wrapper exists. An output-only object is a response and
        // must never be copied into requestFields.
        if (requestRoot == null && responseRoot == null) requestRoot = decoded;
        List<Candidate> requests = extractCandidates(requestRoot);
        List<Candidate> responses = responseRoot == null ? Collections.<Candidate>emptyList() : extractCandidates(responseRoot);
        Map<String, Candidate> responseByKey = new LinkedHashMap<String, Candidate>();
        Map<String, Candidate> responseByAccount = new LinkedHashMap<String, Candidate>();Set<String> repeatedAccounts=new LinkedHashSet<String>();
        for (Candidate candidate : responses) {responseByKey.put(candidate.key(), candidate);if(responseByAccount.containsKey(candidate.accountNumber))repeatedAccounts.add(candidate.accountNumber);else responseByAccount.put(candidate.accountNumber,candidate);}
        for(String repeated:repeatedAccounts)responseByAccount.remove(repeated);
        Map<String, String> genericResponse = responseRoot == null ? Collections.<String,String>emptyMap() : flatten(responseRoot);
        List<Candidate> base = requests.isEmpty() ? responses : requests;
        List<AccountRecord> result = new ArrayList<AccountRecord>();
        for (int i = 0; i < base.size(); i++) {
            Candidate candidate = base.get(i);
            Candidate response = responseByKey.get(candidate.key());
            if (response == null) response = responseByAccount.get(candidate.accountNumber);
            if (response == null && responses.size() == base.size()) response = responses.get(i);
            Map<String, String> responseFields = response == null
                    ? (base.size() == 1 ? genericResponse : Collections.<String,String>emptyMap())
                    : response.fields;
            responseFields = actualResponseFields(responseFields);
            responseFields = addDerivedResponseFields(responseFields);
            result.add(new AccountRecord(candidate.memberNumber, candidate.accountNumber,
                    requests.isEmpty() ? Collections.<String,String>emptyMap() : candidate.fields,
                    responseFields, sourceFile, businessDate, sourceLine));
        }
        return result;
    }

    private Map<String, String> actualResponseFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return Collections.emptyMap();
        Map<String, String> responseOnly = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String[] segments = entry.getKey().split("\\.");
            boolean responsePath = false;
            for (String segment : segments) {
                if ("response".equals(canonical(segment.replaceAll("\\[\\d+\\]", "")))) {
                    responsePath = true;
                    break;
                }
            }
            if (responsePath) responseOnly.put(entry.getKey(), entry.getValue());
        }

        // Historical variants sometimes put channel/action fields directly in
        // output.data without a nested response object.
        return responseOnly.isEmpty()
                ? new LinkedHashMap<String, String>(fields)
                : responseOnly;
    }

    /**
     * In the supplied output, the collections risk level is the portion of
     * response.actionType before the underscore, for example:
     *
     * Very High Risk_Mid Value -> Very High Risk
     * Mid Risk_Low Value       -> Mid Risk
     */
    private Map<String, String> addDerivedResponseFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<String, String>(fields);
        String actionType = flatValue(fields, "actiontype");
        if (!actionType.isEmpty()) {
            int separator = actionType.indexOf('_');
            String riskLevel = separator < 0 ? actionType : actionType.substring(0, separator);
            result.put("riskLevel", riskLevel.trim());
        }
        return result;
    }

    private String flatValue(Map<String, String> fields, String expectedLeaf) {
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String path = entry.getKey();
            int dot = path.lastIndexOf('.');
            String leaf = dot < 0 ? path : path.substring(dot + 1);
            leaf = leaf.replaceAll("\\[\\d+\\]", "");
            if (expectedLeaf.equals(canonical(leaf)) && entry.getValue() != null) {
                return entry.getValue().trim();
            }
        }
        return "";
    }

    private JsonNode findExactDataSection(JsonNode node, String sectionName) throws IOException {
        if (node == null || !node.isContainerNode()) return null;
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findExactDataSection(child, sectionName);
                if (found != null) return found;
            }
            return null;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (sectionName.equals(canonical(field.getKey()))) {
                JsonNode section = decodeEmbedded(field.getValue(), 0);
                if (section != null && section.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> sectionFields = section.fields();
                    while (sectionFields.hasNext()) {
                        Map.Entry<String, JsonNode> sectionField = sectionFields.next();
                        if ("data".equals(canonical(sectionField.getKey()))) {
                            return decodeEmbedded(sectionField.getValue(), 0);
                        }
                    }
                }
                return section;
            }
        }

        fields = node.fields();
        while (fields.hasNext()) {
            JsonNode found = findExactDataSection(fields.next().getValue(), sectionName);
            if (found != null) return found;
        }
        return null;
    }

    private JsonNode decodeEmbedded(JsonNode node, int depth) throws IOException {
        if (node == null || depth > 8) return node;
        if (node.isTextual()) {
            String value = node.asText().trim();
            if ((value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"))) {
                try { return decodeEmbedded(mapper.readTree(value), depth + 1); } catch (IOException ignored) { return node; }
            }
            return node;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            for (JsonNode child : node) copy.add(decodeEmbedded(child, depth + 1));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) { Map.Entry<String, JsonNode> field=fields.next(); copy.set(field.getKey(), decodeEmbedded(field.getValue(), depth + 1)); }
            return copy;
        }
        return node;
    }

    private JsonNode findSection(JsonNode node, List<String> aliases) throws IOException {
        if (node == null || !node.isContainerNode()) return null;
        if (node.isArray()) {
            for (JsonNode child : node) { JsonNode found=findSection(child, aliases); if(found!=null)return found; }
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> fields=node.fields();
        while(fields.hasNext()){
            Map.Entry<String,JsonNode> field=fields.next();
            if(aliases.contains(canonical(field.getKey()))){
                JsonNode found=decodeEmbedded(field.getValue(),0);
                if(found!=null&&found.isObject()){
                    Iterator<Map.Entry<String,JsonNode>> children=found.fields();
                    while(children.hasNext()){Map.Entry<String,JsonNode> child=children.next();if("data".equals(canonical(child.getKey())))return decodeEmbedded(child.getValue(),0);}
                }
                return found;
            }
        }
        fields=node.fields();
        while(fields.hasNext()){JsonNode found=findSection(fields.next().getValue(),aliases);if(found!=null)return found;}
        return null;
    }

    private List<Candidate> extractCandidates(JsonNode root) {
        Map<String,Candidate> found=new LinkedHashMap<String,Candidate>();
        visit(root,"",Collections.<String,String>emptyMap(),found);
        return new ArrayList<Candidate>(found.values());
    }

    private void visit(JsonNode node,String inheritedMember,Map<String,String> inheritedFields,Map<String,Candidate> found){
        if(node==null)return;
        if(node.isArray()){for(JsonNode child:node)visit(child,inheritedMember,inheritedFields,found);return;}
        if(!node.isObject())return;
        String member=directScalar(node,MEMBER_ALIASES); if(member.isEmpty())member=inheritedMember;
        String account=directScalar(node,ACCOUNT_ALIASES);
        Map<String,String> context=new LinkedHashMap<String,String>(inheritedFields); context.putAll(scalarContext(node));
        if(!account.isEmpty()){
            String normalizedMember=member.isEmpty()?"UNKNOWN_MEMBER":member;
            String key=normalizedMember+"::"+account;
            Map<String,String> candidateFields=new LinkedHashMap<String,String>(context); candidateFields.putAll(flatten(node));
            Candidate existing=found.get(key); if(existing!=null){Map<String,String> merged=new LinkedHashMap<String,String>(existing.fields);merged.putAll(candidateFields);candidateFields=merged;}
            found.put(key,new Candidate(normalizedMember,account,candidateFields));
        }
        Iterator<JsonNode> children=node.elements(); while(children.hasNext()){JsonNode child=children.next();if(child.isContainerNode())visit(child,member,context,found);}
    }

    private String directScalar(JsonNode node,List<String> aliases){
        Iterator<Map.Entry<String,JsonNode>> fields=node.fields();
        while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();if(aliases.contains(canonical(field.getKey()))&&field.getValue().isValueNode()){String value=field.getValue().asText("").trim();if(!value.isEmpty())return value;}}
        fields=node.fields();
        while(fields.hasNext()){
            Map.Entry<String,JsonNode> field=fields.next();String key=canonical(field.getKey());
            if(Arrays.asList("account","application","member","customer","applicant","identifiers").contains(key)&&field.getValue().isObject()){String value=directScalar(field.getValue(),aliases);if(!value.isEmpty())return value;}
        }
        return "";
    }

    private Map<String,String> scalarContext(JsonNode node){
        Map<String,String> result=new LinkedHashMap<String,String>();Iterator<Map.Entry<String,JsonNode>> fields=node.fields();
        while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();JsonNode value=field.getValue();if(value.isValueNode()||isPrimitiveArray(value))result.put(field.getKey(),displayValue(value));}
        return result;
    }

    private Map<String,String> flatten(JsonNode node){Map<String,String> result=new LinkedHashMap<String,String>();flatten(node,"",result);return result;}
    private void flatten(JsonNode node,String path,Map<String,String> target){
        if(node==null||node.isNull()){if(!path.isEmpty())target.put(path,"");return;}
        if(node.isValueNode()){if(!path.isEmpty())target.put(path,node.asText());return;}
        if(node.isArray()){
            if(isPrimitiveArray(node)){if(!path.isEmpty())target.put(path,displayValue(node));return;}
            int index=0;for(JsonNode child:node)flatten(child,path+"["+(index++)+"]",target);return;
        }
        Iterator<Map.Entry<String,JsonNode>> fields=node.fields();
        while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();flatten(field.getValue(),path.isEmpty()?field.getKey():path+"."+field.getKey(),target);}
    }

    private boolean isPrimitiveArray(JsonNode node){if(!node.isArray())return false;for(JsonNode child:node)if(child.isContainerNode())return false;return true;}
    private String displayValue(JsonNode node){
        if(!node.isArray())return node.asText("");List<String> values=new ArrayList<String>();for(JsonNode child:node)values.add(child.asText(""));return String.join(", ",values);
    }

    public List<String> getAvailableDates(){return new ArrayList<String>(snapshots.keySet());}
    public String resolveDate(String requested){if(requested!=null&&snapshots.containsKey(requested))return requested;List<String> dates=getAvailableDates();return dates.isEmpty()?null:dates.get(dates.size()-1);}
    public DailySnapshot getSnapshot(String date){return date==null?null:snapshots.get(date);}
    public int getLoadedDateCount(){return snapshots.size();}
    public String getDataDirectory(){return dataDirectory.toString();}
    public long getLastScanTimeMs(){return lastScanTimeMs;}
    public String getLastLoadError(){return lastLoadError;}

    public String getPreviousDate(String currentDate){if(currentDate==null)return null;List<String> dates=getAvailableDates();int index=dates.indexOf(currentDate);return index>0?dates.get(index-1):null;}

    public List<AccountDayView> findAccountDayViews(String currentDate,String memberFilter){
        DailySnapshot current=getSnapshot(currentDate);DailySnapshot previous=getSnapshot(getPreviousDate(currentDate));
        Map<String,AccountRecord> right=current==null?Collections.<String,AccountRecord>emptyMap():current.asAccountMap();
        Map<String,AccountRecord> left=previous==null?Collections.<String,AccountRecord>emptyMap():previous.asAccountMap();
        Set<String> keys=new LinkedHashSet<String>();keys.addAll(left.keySet());keys.addAll(right.keySet());
        List<AccountDayView> result=new ArrayList<AccountDayView>();
        for(String key:keys){AccountRecord before=left.get(key),after=right.get(key);AccountRecord record=after==null?before:after;if(memberFilter!=null&&!memberFilter.isEmpty()&&!record.getMemberNumber().equals(memberFilter))continue;
            List<FieldChange> changes=changes(before,after);ChangeType type=before==null?ChangeType.NEW:after==null?ChangeType.MISSING:changes.isEmpty()?ChangeType.UNCHANGED:ChangeType.CHANGED;
            result.add(new AccountDayView(after,before,type,changes));}
        Collections.sort(result,new Comparator<AccountDayView>(){public int compare(AccountDayView a,AccountDayView b){int member=a.getAccount().getMemberNumber().compareTo(b.getAccount().getMemberNumber());return member!=0?member:a.getAccount().getAccountNumber().compareTo(b.getAccount().getAccountNumber());}});
        return result;
    }

    private List<FieldChange> changes(AccountRecord before,AccountRecord after){
        if(before==null||after==null)return Collections.emptyList();Map<String,String> left=combined(before),right=combined(after);Set<String> fields=new LinkedHashSet<String>();fields.addAll(left.keySet());fields.addAll(right.keySet());List<FieldChange> result=new ArrayList<FieldChange>();
        for(String field:fields){String a=left.containsKey(field)?left.get(field):"";String b=right.containsKey(field)?right.get(field):"";if(!a.equals(b))result.add(new FieldChange(normalizeIndex(field),a,b));}
        return result;
    }

    private Map<String,String> combined(AccountRecord record){Map<String,String> result=new LinkedHashMap<String,String>();result.putAll(record.getRequestFields());result.putAll(record.getResponseFields());return result;}
    private String normalizeIndex(String field){return field.replaceAll("\\[\\d+\\]","[]");}

    public ComparisonSummary getComparisonSummary(String currentDate,String memberFilter){
        List<AccountDayView> views=findAccountDayViews(currentDate,memberFilter);ComparisonSummary summary=new ComparisonSummary();summary.setCurrentDate(currentDate);summary.setPreviousDate(getPreviousDate(currentDate));
        DailySnapshot current=getSnapshot(currentDate);summary.setCurrentAccounts(current==null?0:current.getAccountCount());Map<String,Long> risks=new LinkedHashMap<String,Long>();Map<String,Long> channels=new LinkedHashMap<String,Long>();Map<String,Long> paidOffChannels=new LinkedHashMap<String,Long>();Map<String,BigDecimal> paidOffAmounts=new LinkedHashMap<String,BigDecimal>();Map<String,Long> paidOffAmountCounts=new LinkedHashMap<String,Long>();Map<String,BigDecimal> collectedAmounts=new LinkedHashMap<String,BigDecimal>();Map<String,Long> collectedAmountCounts=new LinkedHashMap<String,Long>();
        for(AccountDayView view:views){if(view.getChangeType()==ChangeType.NEW)summary.setNewAccounts(summary.getNewAccounts()+1);else if(view.getChangeType()==ChangeType.MISSING)summary.setMissingAccounts(summary.getMissingAccounts()+1);else if(view.getChangeType()==ChangeType.CHANGED)summary.setChangedAccounts(summary.getChangedAccounts()+1);else summary.setUnchangedAccounts(summary.getUnchangedAccounts()+1);
            if(view.isRiskChanged()){summary.setRiskLevelChanged(summary.getRiskLevelChanged()+1);increment(risks,view.getRiskBefore(),view.getRiskAfter());}
            if(view.isChannelChanged()){summary.setChannelChanged(summary.getChannelChanged()+1);increment(channels,view.getChannelBefore(),view.getChannelAfter());}
            if(view.getChangeType()==ChangeType.MISSING){String channel=view.getChannelBefore().isEmpty()?"Not provided":view.getChannelBefore();paidOffChannels.put(channel,paidOffChannels.containsKey(channel)?paidOffChannels.get(channel)+1:1);BigDecimal amount=paidOffAmount(view.getPrevious());summary.setTotalPaidOffAmount(summary.getTotalPaidOffAmount().add(amount));addAmount(paidOffAmounts,paidOffAmountCounts,channel,amount);}
            BigDecimal collected=collectedAmount(view);if(collected.signum()>0){String channel=view.getChannelAfter().isEmpty()?"Not provided":view.getChannelAfter();summary.setTotalCollectedAmount(summary.getTotalCollectedAmount().add(collected));addAmount(collectedAmounts,collectedAmountCounts,channel,collected);}}
        summary.getRiskTransitions().addAll(toTransitions(risks));summary.getChannelTransitions().addAll(toTransitions(channels));summary.getPaidOffByChannel().addAll(toChannelCounts(paidOffChannels));summary.getPaidOffAmountByChannel().addAll(toChannelAmounts(paidOffAmounts,paidOffAmountCounts));summary.getCollectedAmountByChannel().addAll(toChannelAmounts(collectedAmounts,collectedAmountCounts));return summary;
    }

    private void increment(Map<String,Long> counts,String from,String to){String key=from+"|||TRANSITION|||"+to;counts.put(key,counts.containsKey(key)?counts.get(key)+1:1);}
    private List<TransitionCount> toTransitions(Map<String,Long> counts){List<TransitionCount> result=new ArrayList<TransitionCount>();for(Map.Entry<String,Long> entry:counts.entrySet()){String[] parts=entry.getKey().split("\\|\\|\\|TRANSITION\\|\\|\\|",-1);result.add(new TransitionCount(parts[0],parts[1],entry.getValue()));}Collections.sort(result,new Comparator<TransitionCount>(){public int compare(TransitionCount a,TransitionCount b){return Long.compare(b.getCount(),a.getCount());}});return result;}
    private List<ChannelCount> toChannelCounts(Map<String,Long> counts){List<ChannelCount> result=new ArrayList<ChannelCount>();for(Map.Entry<String,Long> entry:counts.entrySet())result.add(new ChannelCount(entry.getKey(),entry.getValue()));Collections.sort(result,new Comparator<ChannelCount>(){public int compare(ChannelCount a,ChannelCount b){return Long.compare(b.getCount(),a.getCount());}});return result;}

    /** Accounts sent to each channel on one business date. */
    public List<ChannelCount> getChannelDistribution(String businessDate) {
        DailySnapshot snapshot = getSnapshot(businessDate);
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        if (snapshot != null) {
            for (AccountRecord account : snapshot.asAccountMap().values()) {
                String channel = account.getChannel().isEmpty() ? "Not provided" : account.getChannel();
                counts.put(channel, counts.containsKey(channel) ? counts.get(channel) + 1 : 1);
            }
        }
        return toChannelCounts(counts);
    }

    /** Accounts sent to each channel for every loaded date. */
    public Map<String, List<ChannelCount>> getChannelDistributionByDate() {
        Map<String, List<ChannelCount>> result = new LinkedHashMap<String, List<ChannelCount>>();
        for (String date : getAvailableDates()) result.put(date, getChannelDistribution(date));
        return result;
    }

    /** Detailed from-channel -> to-channel movements for the selected date. */
    public List<TransitionCount> getChannelChangeDetails(String currentDate) {
        return getComparisonSummary(currentDate, "").getChannelTransitions();
    }

    /**
     * Accounts missing on the selected date, grouped by their previous channel.
     * These are verification candidates and are not automatically confirmed paid off.
     */
    public List<ChannelCount> getMissingAccountsByPreviousChannel(String currentDate) {
        return getComparisonSummary(currentDate, "").getPaidOffByChannel();
    }

    /** All process discrepancies and historical anomalies for the selected date. */
    public List<ProcessingAnomaly> getDiscrepancies(String currentDate) {
        return getVerificationSummary(currentDate).getAnomalies();
    }

    private void addAmount(Map<String,BigDecimal> amounts,Map<String,Long> counts,String channel,BigDecimal amount){amounts.put(channel,(amounts.containsKey(channel)?amounts.get(channel):BigDecimal.ZERO).add(amount));counts.put(channel,(counts.containsKey(channel)?counts.get(channel):0)+1);}
    private List<ChannelAmount> toChannelAmounts(Map<String,BigDecimal> amounts,Map<String,Long> counts){List<ChannelAmount> result=new ArrayList<ChannelAmount>();for(Map.Entry<String,BigDecimal> entry:amounts.entrySet())result.add(new ChannelAmount(entry.getKey(),counts.get(entry.getKey()),entry.getValue()));Collections.sort(result,new Comparator<ChannelAmount>(){public int compare(ChannelAmount a,ChannelAmount b){return b.getAmount().compareTo(a.getAmount());}});return result;}
    private BigDecimal paidOffAmount(AccountRecord previous){if(previous==null)return BigDecimal.ZERO;BigDecimal balance=previous.getAccountBalance();return balance.signum()>0?balance:previous.getAmountPastDue();}
    private BigDecimal collectedAmount(AccountDayView view){if(view.getCurrent()==null)return BigDecimal.ZERO;BigDecimal explicit=view.getCurrent().getExplicitAmountCollected();if(explicit.signum()>0)return explicit;if(view.getPrevious()==null)return BigDecimal.ZERO;BigDecimal reduction=view.getPrevious().getAmountPastDue().subtract(view.getCurrent().getAmountPastDue());return reduction.signum()>0?reduction:BigDecimal.ZERO;}

    public List<CollectedAccountView> getCollectedAccountViews(String currentDate,String channelFilter){List<CollectedAccountView> result=new ArrayList<CollectedAccountView>();for(AccountDayView view:findAccountDayViews(currentDate,"")){BigDecimal amount=collectedAmount(view);if(amount.signum()<=0)continue;String channel=view.getChannelAfter().isEmpty()?"Not provided":view.getChannelAfter();if(channelFilter!=null&&!channelFilter.trim().isEmpty()&&!channel.equalsIgnoreCase(channelFilter.trim()))continue;String method=view.getCurrent().getExplicitAmountCollected().signum()>0?"Explicit collected/payment amount":"Decrease in amount past due";result.add(new CollectedAccountView(view.getCurrent(),view.getPrevious(),channel,amount,method));}Collections.sort(result,new Comparator<CollectedAccountView>(){public int compare(CollectedAccountView a,CollectedAccountView b){return b.getAmountCollected().compareTo(a.getAmountCollected());}});return result;}

    public List<AccountDayView> getPaidOffCandidates(String currentDate,String memberFilter){
        List<AccountDayView> result=new ArrayList<AccountDayView>();for(AccountDayView view:findAccountDayViews(currentDate,memberFilter))if(view.getChangeType()==ChangeType.MISSING)result.add(view);return result;
    }

    public VerificationSummary getVerificationSummary(String currentDate) {
        VerificationSummary result=new VerificationSummary();result.setCurrentDate(currentDate);result.setPreviousDate(getPreviousDate(currentDate));
        DailySnapshot current=getSnapshot(currentDate);DailySnapshot previous=getSnapshot(result.getPreviousDate());
        if(current==null){result.setStatus("FAIL");result.getAnomalies().add(anomaly("ERROR","FILE_LOAD","","","No snapshot was loaded for the selected business date.","A readable daily file",currentDate,""));return result;}
        result.setCurrentApplications(current.getAccountCount());result.setFilesProcessed(current.getSourceFiles().size());result.setPreviousApplications(previous==null?0:previous.getAccountCount());result.setRejectedRecords(current.getRejectedLines());result.setDuplicateApplications(current.getDuplicateApplications());

        List<String> dates=getAvailableDates();int currentIndex=dates.indexOf(currentDate);List<DailySnapshot> history=new ArrayList<DailySnapshot>();
        for(int i=0;i<currentIndex;i++){DailySnapshot snapshot=snapshots.get(dates.get(i));if(snapshot!=null)history.add(snapshot);}
        result.setHistoricalDays(history.size());double volumeTotal=0;for(DailySnapshot snapshot:history)volumeTotal+=snapshot.getAccountCount();double volumeAverage=history.isEmpty()?0:volumeTotal/history.size();result.setHistoricalAverageApplications(volumeAverage);
        double variance=volumeAverage==0?0:((current.getAccountCount()-volumeAverage)/volumeAverage)*100.0;result.setVolumeVariancePercent(variance);

        if(history.size()<2)result.getAnomalies().add(anomaly("WARNING","HISTORY","","","There is not enough historical data for a stable trend baseline.","At least 2 prior business dates",String.valueOf(history.size()),""));
        if(current.getAccountCount()==0&&previous!=null&&previous.getAccountCount()>0)result.getAnomalies().add(anomaly("ERROR","VOLUME","","","No applications were parsed for the current run.",String.valueOf(previous.getAccountCount()),"0",String.join(", ",current.getSourceFiles())));
        else if(volumeAverage>0&&Math.abs(variance)>=40)result.getAnomalies().add(anomaly("ERROR","VOLUME","","","Application volume is materially outside the historical baseline.",format(volumeAverage)+" average",format(current.getAccountCount())+" ("+signedPercent(variance)+")",String.join(", ",current.getSourceFiles())));
        else if(volumeAverage>0&&Math.abs(variance)>=20)result.getAnomalies().add(anomaly("WARNING","VOLUME","","","Application volume is outside the normal historical range.",format(volumeAverage)+" average",format(current.getAccountCount())+" ("+signedPercent(variance)+")",String.join(", ",current.getSourceFiles())));
        if(current.getRejectedLines()>0)result.getAnomalies().add(anomaly("ERROR","PARSING","","","One or more source records could not be parsed.","0 rejected",String.valueOf(current.getRejectedLines())+" rejected",String.join(", ",current.getSourceFiles())));
        if(current.getDuplicateApplications()>0)result.getAnomalies().add(anomaly("ERROR","DUPLICATE","","","Duplicate member/application keys were found in the same business date.","0 duplicates",String.valueOf(current.getDuplicateApplications())+" duplicates",String.join(", ",current.getSourceFiles())));

        Map<String,AccountRecord> currentAccounts=current.asAccountMap();Map<String,AccountRecord> previousAccounts=previous==null?Collections.<String,AccountRecord>emptyMap():previous.asAccountMap();
        Set<String> olderKeys=new LinkedHashSet<String>();for(int i=0;i<currentIndex-1;i++){DailySnapshot old=snapshots.get(dates.get(i));if(old!=null)olderKeys.addAll(old.asAccountMap().keySet());}
        for(Map.Entry<String,AccountRecord> entry:currentAccounts.entrySet()){
            AccountRecord account=entry.getValue();
            if(!account.isResponseAvailable()){result.setMissingResponses(result.getMissingResponses()+1);result.getAnomalies().add(anomaly("ERROR","MISSING_RESPONSE",account.getMemberNumber(),account.getAccountNumber(),"The application was parsed but no decision response was matched.","Matched response element","No response",source(account)));}
            else {
                if(account.getChannel().isEmpty())result.getAnomalies().add(anomaly("WARNING","MISSING_CHANNEL",account.getMemberNumber(),account.getAccountNumber(),"The processed response does not contain a recognized channel.","Channel populated","Blank",source(account)));
                if(account.getRiskLevel().isEmpty())result.getAnomalies().add(anomaly("WARNING","MISSING_RISK",account.getMemberNumber(),account.getAccountNumber(),"The processed response does not contain a recognized risk level.","Risk level populated","Blank",source(account)));
            }
            if(!previousAccounts.containsKey(entry.getKey())&&olderKeys.contains(entry.getKey())){result.setReappearedApplications(result.getReappearedApplications()+1);result.getAnomalies().add(anomaly("WARNING","REAPPEARED",account.getMemberNumber(),account.getAccountNumber(),"The application reappeared after being absent on the previous business date.","Consistent daily presence","Absent then present",source(account)));}
        }

        List<AccountDayView> paidOff=getPaidOffCandidates(currentDate,"");result.setPaidOffCandidates(paidOff.size());
        for(AccountDayView view:paidOff){AccountRecord account=view.getPrevious();result.getAnomalies().add(anomaly("REVIEW","PAID_OFF_CANDIDATE",account.getMemberNumber(),account.getAccountNumber(),"The account is absent from the current file and may be paid off. Verify in the source system.","Present on "+result.getPreviousDate(),"Absent on "+currentDate,source(account)));}

        ComparisonSummary comparison=getComparisonSummary(currentDate,"");double riskAverage=historicalComparisonAverage(dates,currentIndex,"risk");double channelAverage=historicalComparisonAverage(dates,currentIndex,"channel");double paidOffAverage=historicalComparisonAverage(dates,currentIndex,"paidOff");
        if(comparison.getRiskLevelChanged()>=5&&comparison.getRiskLevelChanged()>Math.max(1,riskAverage*2))result.getAnomalies().add(anomaly("WARNING","RISK_MOVEMENT","","","Risk-level changes are unusually high compared with prior daily runs.",format(riskAverage)+" historical average",String.valueOf(comparison.getRiskLevelChanged()),""));
        if(comparison.getChannelChanged()>=5&&comparison.getChannelChanged()>Math.max(1,channelAverage*2))result.getAnomalies().add(anomaly("WARNING","CHANNEL_MOVEMENT","","","Channel changes are unusually high compared with prior daily runs.",format(channelAverage)+" historical average",String.valueOf(comparison.getChannelChanged()),""));
        if(comparison.getMissingAccounts()>=5&&comparison.getMissingAccounts()>Math.max(1,paidOffAverage*2))result.getAnomalies().add(anomaly("WARNING","PAID_OFF_VOLUME","","","Paid-off candidates are unusually high compared with prior daily runs.",format(paidOffAverage)+" historical average",String.valueOf(comparison.getMissingAccounts()),""));

        Map<String,Double> historicalByChannel=historicalPaidOffChannelAverages(dates,currentIndex);
        for(ChannelCount channel:comparison.getPaidOffByChannel()){double expected=historicalByChannel.containsKey(channel.getChannel())?historicalByChannel.get(channel.getChannel()):0;if(channel.getCount()>=3&&channel.getCount()>Math.max(1,expected*2))result.getAnomalies().add(anomaly("WARNING","PAID_OFF_CHANNEL","","","Paid-off candidates from "+channel.getChannel()+" are unusually high.",format(expected)+" historical average",String.valueOf(channel.getCount()),""));}

        if(result.getErrorCount()>0)result.setStatus("FAIL");else if(result.getWarningCount()>0||result.getReviewCount()>0)result.setStatus("REVIEW");else result.setStatus("PASS");return result;
    }

    private double historicalComparisonAverage(List<String> dates,int currentIndex,String metric){double total=0;int count=0;for(int i=1;i<currentIndex;i++){ComparisonSummary summary=getComparisonSummary(dates.get(i),"");if("risk".equals(metric))total+=summary.getRiskLevelChanged();else if("channel".equals(metric))total+=summary.getChannelChanged();else total+=summary.getMissingAccounts();count++;}return count==0?0:total/count;}
    private Map<String,Double> historicalPaidOffChannelAverages(List<String> dates,int currentIndex){Map<String,Double> totals=new LinkedHashMap<String,Double>();int days=0;for(int i=1;i<currentIndex;i++){for(ChannelCount item:getComparisonSummary(dates.get(i),"").getPaidOffByChannel())totals.put(item.getChannel(),(totals.containsKey(item.getChannel())?totals.get(item.getChannel()):0)+item.getCount());days++;}if(days>0)for(String key:new ArrayList<String>(totals.keySet()))totals.put(key,totals.get(key)/days);return totals;}
    private ProcessingAnomaly anomaly(String severity,String category,String member,String account,String message,String expected,String actual,String source){return new ProcessingAnomaly(severity,category,member,account,message,expected,actual,source);}
    private String source(AccountRecord account){return account.getSourceFile()+" : "+account.getSourceLine();}
    private String format(double value){return String.format(Locale.ROOT,"%.1f",value);}
    private String format(long value){return String.valueOf(value);}
    private String signedPercent(double value){return String.format(Locale.ROOT,"%+.1f%%",value);}

    public List<DailySummary> getDailySummaries(){
        List<DailySummary> result=new ArrayList<DailySummary>();for(String date:getAvailableDates()){DailySnapshot snapshot=snapshots.get(date);ComparisonSummary comparison=getComparisonSummary(date,"");DailySummary summary=new DailySummary();summary.setBusinessDate(date);summary.setSourceFile(String.join(", ",snapshot.getSourceFiles()));summary.setFilesProcessed(snapshot.getSourceFiles().size());summary.setMemberCount(snapshot.getMemberCount());summary.setAccountCount(snapshot.getAccountCount());summary.setTotalLines(snapshot.getTotalLines());summary.setParsedLines(snapshot.getParsedLines());summary.setRejectedLines(snapshot.getRejectedLines());summary.setDuplicateApplications(snapshot.getDuplicateApplications());summary.setNewAccounts(comparison.getNewAccounts());summary.setMissingAccounts(comparison.getMissingAccounts());summary.setChangedAccounts(comparison.getChangedAccounts());summary.setRiskLevelChanged(comparison.getRiskLevelChanged());summary.setChannelChanged(comparison.getChannelChanged());summary.setPaidOffAmount(comparison.getTotalPaidOffAmount());summary.setCollectedAmount(comparison.getTotalCollectedAmount());result.add(summary);}return result;
    }

    public HistoricalReport getHistoricalReport(){
        HistoricalReport report=new HistoricalReport();List<String> dates=getAvailableDates();List<DailySummary> daily=getDailySummaries();report.getDailySummaries().addAll(daily);report.setBusinessDates(dates.size());report.setLatestDate(dates.isEmpty()?null:dates.get(dates.size()-1));
        long totalLines=0,parsedLines=0;Map<String,Long> risks=new LinkedHashMap<String,Long>();Map<String,Long> channels=new LinkedHashMap<String,Long>();Map<String,Long> paidOffChannels=new LinkedHashMap<String,Long>();Map<String,BigDecimal> historicalPaidOffAmounts=new LinkedHashMap<String,BigDecimal>();Map<String,Long> historicalPaidOffCounts=new LinkedHashMap<String,Long>();Map<String,BigDecimal> historicalCollectedAmounts=new LinkedHashMap<String,BigDecimal>();Map<String,Long> historicalCollectedCounts=new LinkedHashMap<String,Long>();
        for(DailySummary summary:daily){report.setTotalFilesProcessed(report.getTotalFilesProcessed()+summary.getFilesProcessed());report.setTotalApplications(report.getTotalApplications()+summary.getAccountCount());report.setTotalChanged(report.getTotalChanged()+summary.getChangedAccounts());report.setTotalRiskChanged(report.getTotalRiskChanged()+summary.getRiskLevelChanged());report.setTotalChannelChanged(report.getTotalChannelChanged()+summary.getChannelChanged());report.setTotalPaidOffCandidates(report.getTotalPaidOffCandidates()+summary.getMissingAccounts());report.setTotalRejectedRecords(report.getTotalRejectedRecords()+summary.getRejectedLines());totalLines+=summary.getTotalLines();parsedLines+=summary.getParsedLines();}
        report.setAverageDailyApplications(dates.isEmpty()?0:(double)report.getTotalApplications()/dates.size());report.setParseSuccessRate(totalLines==0?100.0:(double)parsedLines*100.0/totalLines);
        for(int i=1;i<dates.size();i++){ComparisonSummary comparison=getComparisonSummary(dates.get(i),"");report.setTotalPaidOffAmount(report.getTotalPaidOffAmount().add(comparison.getTotalPaidOffAmount()));report.setTotalCollectedAmount(report.getTotalCollectedAmount().add(comparison.getTotalCollectedAmount()));for(TransitionCount item:comparison.getRiskTransitions())addTransition(risks,item);for(TransitionCount item:comparison.getChannelTransitions())addTransition(channels,item);for(ChannelCount item:comparison.getPaidOffByChannel())paidOffChannels.put(item.getChannel(),(paidOffChannels.containsKey(item.getChannel())?paidOffChannels.get(item.getChannel()):0)+item.getCount());for(ChannelAmount item:comparison.getPaidOffAmountByChannel())addHistoricalAmount(historicalPaidOffAmounts,historicalPaidOffCounts,item);for(ChannelAmount item:comparison.getCollectedAmountByChannel())addHistoricalAmount(historicalCollectedAmounts,historicalCollectedCounts,item);}
        report.getRiskTransitions().addAll(toTransitions(risks));report.getChannelTransitions().addAll(toTransitions(channels));report.getPaidOffByChannel().addAll(toChannelCounts(paidOffChannels));report.getPaidOffAmountByChannel().addAll(toChannelAmounts(historicalPaidOffAmounts,historicalPaidOffCounts));report.getCollectedAmountByChannel().addAll(toChannelAmounts(historicalCollectedAmounts,historicalCollectedCounts));
        if(!dates.isEmpty()){DailySnapshot latest=snapshots.get(dates.get(dates.size()-1));Map<String,Long> channelDistribution=new LinkedHashMap<String,Long>();Map<String,Long> riskDistribution=new LinkedHashMap<String,Long>();for(AccountRecord account:latest.asAccountMap().values()){String channel=account.getChannel().isEmpty()?"Not provided":account.getChannel();String risk=account.getRiskLevel().isEmpty()?"Not provided":account.getRiskLevel();channelDistribution.put(channel,(channelDistribution.containsKey(channel)?channelDistribution.get(channel):0)+1);riskDistribution.put(risk,(riskDistribution.containsKey(risk)?riskDistribution.get(risk):0)+1);}report.getCurrentChannelDistribution().addAll(toChannelCounts(channelDistribution));report.getCurrentRiskDistribution().addAll(toChannelCounts(riskDistribution));}
        return report;
    }

    private void addTransition(Map<String,Long> totals,TransitionCount item){String key=item.getFromValue()+"|||TRANSITION|||"+item.getToValue();totals.put(key,(totals.containsKey(key)?totals.get(key):0)+item.getCount());}
    private void addHistoricalAmount(Map<String,BigDecimal> amounts,Map<String,Long> counts,ChannelAmount item){amounts.put(item.getChannel(),(amounts.containsKey(item.getChannel())?amounts.get(item.getChannel()):BigDecimal.ZERO).add(item.getAmount()));counts.put(item.getChannel(),(counts.containsKey(item.getChannel())?counts.get(item.getChannel()):0)+item.getAccountCount());}

    private String businessDate(String fileName){
        Matcher iso=ISO_DATE.matcher(fileName);if(iso.find())return validDate(iso.group(1)+"-"+iso.group(2)+"-"+iso.group(3));Matcher us=US_DATE.matcher(fileName);if(us.find())return validDate(us.group(3)+"-"+us.group(1)+"-"+us.group(2));return "UNDATED";
    }
    private String validDate(String date){try{return LocalDate.parse(date,DateTimeFormatter.ISO_LOCAL_DATE).toString();}catch(DateTimeParseException ex){return "UNDATED";}}
    private String canonical(String value){return value==null?"":value.replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.ROOT);}

    private static class Candidate {
        private final String memberNumber;private final String accountNumber;private final Map<String,String> fields;
        private Candidate(String memberNumber,String accountNumber,Map<String,String> fields){this.memberNumber=memberNumber;this.accountNumber=accountNumber;this.fields=fields;}
        private String key(){return memberNumber+"::"+accountNumber;}
    }
}
