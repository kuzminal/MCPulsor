package ru.kuzmin.mcpulsor.server;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import tools.jackson.databind.json.JsonMapper;

public class McpulsorServerApplication {
    public static void main(String[] args) throws Exception {
        IO.println("Server Application Started");

        var transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcpulsor")
                .build();

        McpSchema.Tool bioSensorTool = McpSchema.Tool.builder(
                        "bioSensor",
                        new JacksonMcpJsonMapper(new JsonMapper()),
                        createBioSensorInputSchema())
                .title("Human Vital Pulse Sensor")
                .description("This sensor measures the pulse rate of a human being.")
                .build();

        McpServerFeatures.SyncToolSpecification bioSensorToolSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(bioSensorTool)
                .callHandler((mcpSyncServerExchange, callToolRequest) ->
                        McpSchema.CallToolResult.builder()
                                .addTextContent("Your pulse rate is 72 bpm.")
                                .isError(false)
                                .build())
                .build();

        McpServer.sync(transportProvider)
                .serverInfo("Mcpulsor MCP Server", "1.0.1")
                .capabilities(createServerCapabilities())
                .tools(bioSensorToolSpec)
                .build();

        Server server = new Server(8081);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(transportProvider), "/*");

        server.setHandler(contextHandler);
        server.start();
        server.join();
    }

    private static String createBioSensorInputSchema() {
        return new JsonMapper().createObjectNode().put("type", "object").toString();
    }

    private static McpSchema.ServerCapabilities createServerCapabilities() {
        return McpSchema.ServerCapabilities.builder().tools(true).build();
    }
}
