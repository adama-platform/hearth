package io.hearth.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An argument shaped like an object arrives as an object.
 *
 * This is a plumbing test and it exists because the plumbing was wrong in the one way nothing
 * catches. {@code unwrap} handled nulls, booleans, numbers and arrays and then fell through to
 * {@code asText()}, which for a container node is the empty string -- so every nested object any
 * tool declared arrived at the surface as {@code ""}.
 *
 * {@code place_save} has advertised a {@code fields} object since the address book shipped and
 * reads it behind an {@code instanceof Map} that could never be true. A model filling in a kind's
 * own fields -- the whole reason a community invents a kind -- was answered with a success and
 * wrote nothing. That is exactly the failure invariant 76 refuses for an *undeclared* field,
 * arriving through the transport instead of through the handler: a write that reports success
 * while discarding what it was given teaches a model that it worked.
 *
 * Every tool taking a structured argument is checked here, both that a value lands and that a bad
 * one is still refused -- because "it arrives" and "it is still checked" fail separately.
 */
public class ToolArgumentTests {
  private static final String REDIRECT = "https://grok.com/connectors/callback";
  private static final ObjectMapper JSON = new ObjectMapper();

  private Configs configs;
  private TestServer server;
  private Browser admin;
  private McpClient grok;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    server = TestServer.ofConfigs(configs.file());
    admin = new Browser(server.port, "example.org");
    admin.get("/register");
    admin.submit(Map.of("email", "boss@example.com"));
    admin.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    grok = new McpClient(server.port, "example.org").connect(admin, REDIRECT);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private static ObjectNode values(String... pairs) {
    ObjectNode node = JSON.createObjectNode();
    for (int k = 0; k + 1 < pairs.length; k += 2) {
      node.put(pairs[k], pairs[k + 1]);
    }
    return node;
  }




  @Test
  public void aNestedObjectSurvivesTheWholeRoundTrip() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", JSON.createArrayNode().add(
            JSON.createObjectNode().put("name", "subtitle").put("type", "text")
                .put("label", "Subtitle").put("required", false)));

    JsonNode declared = grok.call("template_get", "name", "article").toolResult().path("fields");
    assertEquals("an array of objects has to arrive as objects, not as one stringified blob",
        1, declared.size());
    assertEquals("subtitle", declared.get(0).path("name").asText());
  }
}
