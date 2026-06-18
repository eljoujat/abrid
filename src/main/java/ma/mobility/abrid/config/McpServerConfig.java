package ma.mobility.abrid.config;

import ma.mobility.abrid.agent.AgentTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AgentTools} methods as MCP tool callbacks.
 *
 * <p>Spring AI's auto-configuration for {@code spring-ai-starter-mcp-server-webmvc}
 * picks up all {@link ToolCallbackProvider} beans and exposes them through the
 * MCP protocol (SSE over HTTP).
 *
 * <p>MCP endpoints (configurable via {@code spring.ai.mcp.server}):
 * <ul>
 *   <li>SSE stream  : {@code GET  /sse}</li>
 *   <li>Tool calls  : {@code POST /mcp/message}</li>
 * </ul>
 */
@Configuration
public class McpServerConfig {

    /**
     * Exposes all {@code @Tool}-annotated methods in {@link AgentTools} to the MCP server.
     */
    @Bean
    public ToolCallbackProvider abridToolCallbacks(AgentTools agentTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(agentTools)
            .build();
    }
}
