package br.com.nora.api.api.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The static {@code tools/list} catalogue: five read-only tools over meetings, tasks and Customer
 * Confidence (ADR 0041 §4).
 *
 * <p><b>Everything in this file is read by a language model, so nothing here is operational.</b>
 * Tool names and descriptions land verbatim in an external agent's context window. They therefore
 * say what a tool returns and nothing else: no instruction about when to call it, no phrasing that
 * reads as a directive, and above all no tenant data — not a workspace name, not a user, not a
 * count. The catalogue is identical for every caller of every tenant, which is what makes it safe
 * to be constant text.
 *
 * <p><b>The catalogue is not an authorization statement.</b> Listing a tool says the server
 * implements it, never that this caller may use it. Every tool re-derives that on invocation
 * through {@code AuthorizationService}, so a principal with no {@code meeting:read} sees the same
 * five tools and gets refused by all of them. Filtering the list by permission was considered and
 * rejected: it would have put an authorization decision in a cached, client-side artefact.
 */
public final class McpToolCatalog {

    private McpToolCatalog() {}

    public static final String LIST_MEETINGS = "list_meetings";
    public static final String GET_MEETING = "get_meeting";
    public static final String SEARCH_MEETINGS = "search_meetings";
    public static final String LIST_TASKS = "list_tasks";
    public static final String GET_CUSTOMER_CONFIDENCE = "get_customer_confidence";

    /** Upper bound the tools accept for {@code limit}, mirrored in the schemas below. */
    public static final int MAX_LIMIT = 50;

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** The five tool definitions, in the shape {@code tools/list} returns them. */
    public static ArrayNode tools() {
        ArrayNode tools = F.arrayNode();
        tools.add(listMeetings());
        tools.add(getMeeting());
        tools.add(searchMeetings());
        tools.add(listTasks());
        tools.add(getCustomerConfidence());
        return tools;
    }

    private static ObjectNode listMeetings() {
        ObjectNode props = F.objectNode();
        props.set("limit", integerProp("Maximum number of meetings to return.", 1, MAX_LIMIT));
        props.set(
                "offset",
                integerProp("Number of visible meetings to skip before returning.", 0, 100_000));
        props.set(
                "status",
                stringProp(
                        "Optional processing-status filter: PENDING, PROCESSING, COMPLETED or"
                                + " FAILED."));
        props.set("search", stringProp("Optional case-insensitive substring of the title."));
        return tool(
                LIST_MEETINGS,
                "List meetings",
                "Lists meetings of the workspace with their processing status, summary snippet,"
                        + " tags and participant names, newest first.",
                schema(props));
    }

    private static ObjectNode getMeeting() {
        ObjectNode props = F.objectNode();
        props.set("meetingId", stringProp("Identifier of the meeting, as returned by a listing."));
        return tool(
                GET_MEETING,
                "Get meeting detail",
                "Returns one meeting with its participants, tags and, when the analysis has"
                        + " finished, its summary, decisions, action items, risks and"
                        + " opportunities.",
                required(schema(props), "meetingId"));
    }

    private static ObjectNode searchMeetings() {
        ObjectNode props = F.objectNode();
        props.set("query", stringProp("Natural-language description of what is being looked for."));
        props.set("limit", integerProp("Maximum number of meetings to return.", 1, 10));
        return tool(
                SEARCH_MEETINGS,
                "Search meetings by meaning",
                "Finds the meetings most semantically related to a query, ranked by relevance"
                        + " rather than by date. Returns an empty list when the workspace has no"
                        + " indexed meetings.",
                required(schema(props), "query"));
    }

    private static ObjectNode listTasks() {
        ObjectNode props = F.objectNode();
        props.set("status", stringProp("Optional status filter: OPEN, IN_PROGRESS or DONE."));
        props.set("limit", integerProp("Maximum number of tasks to return.", 1, MAX_LIMIT));
        return tool(
                LIST_TASKS,
                "List action items",
                "Lists the action items extracted from the workspace's meetings, with assignee,"
                        + " due date, priority, status and the meeting each one came from.",
                schema(props));
    }

    private static ObjectNode getCustomerConfidence() {
        ObjectNode props = F.objectNode();
        props.set("meetingId", stringProp("Identifier of the meeting, as returned by a listing."));
        return tool(
                GET_CUSTOMER_CONFIDENCE,
                "Get Customer Confidence assessment",
                "Returns the Customer Confidence assessment of one meeting: score, band, trend,"
                        + " account name, rationale, buying signals and objections. Null for"
                        + " internal meetings, which are not assessed.",
                required(schema(props), "meetingId"));
    }

    /* ------------------------------ builders ------------------------------ */

    private static ObjectNode tool(
            String name, String title, String description, ObjectNode inputSchema) {
        ObjectNode tool = F.objectNode();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }

    /** A JSON Schema object node. No {@code $schema} key: the protocol defaults to 2020-12. */
    private static ObjectNode schema(ObjectNode properties) {
        ObjectNode schema = F.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode required(ObjectNode schema, String... names) {
        ArrayNode required = F.arrayNode();
        for (String name : names) {
            required.add(name);
        }
        schema.set("required", required);
        return schema;
    }

    private static ObjectNode stringProp(String description) {
        ObjectNode prop = F.objectNode();
        prop.put("type", "string");
        prop.put("description", description);
        return prop;
    }

    private static ObjectNode integerProp(String description, int minimum, int maximum) {
        ObjectNode prop = F.objectNode();
        prop.put("type", "integer");
        prop.put("description", description);
        prop.put("minimum", minimum);
        prop.put("maximum", maximum);
        return prop;
    }
}
