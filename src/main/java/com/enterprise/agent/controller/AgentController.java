import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @Autowired
    private MultiAgentOrchestrator orchestrator;

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return orchestrator.handleRequest(request);
    }

    @PostMapping("/ingest")
    public void ingest(@RequestParam String directoryPath) {
        // Implementation for document ingestion from the provided directory
    }

    @GetMapping("/stats")
    public RouterCacheStatistics getStats() {
        // Implementation for retrieving router cache statistics
    }
}