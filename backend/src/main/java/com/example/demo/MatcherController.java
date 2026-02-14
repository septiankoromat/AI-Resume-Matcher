package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@RestController
@RequestMapping("/api/matcher")
@CrossOrigin(origins = "http://localhost:5173") // This lets your React app talk to Java
public class MatcherController {

    private static final Logger logger = LoggerFactory.getLogger(MatcherController.class);
    private final ChatClient chatClient;
    private final ResumeService resumeService;

    // Constructor to inject the AI client and our Resume service
    public MatcherController(ChatClient.Builder builder, ResumeService resumeService) {
        this.chatClient = builder.build();
        this.resumeService = resumeService;
    }

    @PostMapping("/match")
    public ResponseEntity<?> getMatch(@RequestParam("file") MultipartFile file,
                           @RequestParam("jd") String jd) {
        try {
            // Validate inputs
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body("Resume file is required");
            }
            if (jd == null || jd.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Job description is required");
            }
            if (!"application/pdf".equals(file.getContentType())) {
                return ResponseEntity.badRequest().body("Only PDF files are supported");
            }

            // 1. Convert PDF to Text
            String resumeText = resumeService.extractText(file);
            if (resumeText == null || resumeText.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Could not extract text from PDF. Please ensure the PDF contains readable text.");
            }

            // 2. Prepare the instructions for the AI
            String prompt = "Compare this Resume: " + resumeText +
                    " with this Job Description: " + jd +
                    ". Provide a JSON response with the following structure: " +
                    "{\"matchPercentage\": <number between 0-100>, \"topTips\": [\"tip1\", \"tip2\", \"tip3\"]}. " +
                    "The matchPercentage should be a number representing how well the resume matches the job description. " +
                    "The topTips should be an array of exactly 3 specific, actionable improvement suggestions. " +
                    "Return ONLY valid JSON, no additional text.";

            // 3. Call the AI and return the answer
            logger.info("Calling Gemini API with prompt length: {}", prompt.length());
            String aiResponse = chatClient.prompt(prompt).call().content();
            logger.info("Successfully received response from Gemini API");
            
            // Parse the AI response to extract structured data
            Map<String, Object> result = parseAIResponse(aiResponse);
            return ResponseEntity.ok(result);
            
        } catch (IOException e) {
            logger.error("Error processing PDF", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing PDF: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error calling Gemini API", e);
            String errorMsg = "Error calling AI service: " + e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                errorMsg += " (Cause: " + cause.getMessage() + ")";
                cause = cause.getCause();
            }
            errorMsg += "\n\nTroubleshooting:\n";
            errorMsg += "1. Verify your Google Gemini API key is valid in application.properties\n";
            errorMsg += "2. Check that the API key has Gemini API access enabled\n";
            errorMsg += "3. Ensure you haven't exceeded API quota limits\n";
            errorMsg += "4. Check backend logs for more details";
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorMsg);
        }
    }
    
    private Map<String, Object> parseAIResponse(String aiResponse) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Try to extract JSON from the response
            String jsonStr = aiResponse.trim();
            int jsonStart = jsonStr.indexOf("{");
            int jsonEnd = jsonStr.lastIndexOf("}") + 1;
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonStr = jsonStr.substring(jsonStart, jsonEnd);
                // Simple JSON parsing for our specific structure
                // Extract matchPercentage
                Pattern percentPattern = Pattern.compile("\"matchPercentage\"\\s*:\\s*(\\d+)");
                Matcher percentMatcher = percentPattern.matcher(jsonStr);
                if (percentMatcher.find()) {
                    result.put("matchPercentage", Integer.parseInt(percentMatcher.group(1)));
                }
                
                // Extract topTips array
                Pattern tipsPattern = Pattern.compile("\"topTips\"\\s*:\\s*\\[([^\\]]+)\\]");
                Matcher tipsMatcher = tipsPattern.matcher(jsonStr);
                if (tipsMatcher.find()) {
                    String tipsStr = tipsMatcher.group(1);
                    List<String> tips = new ArrayList<>();
                    Pattern tipPattern = Pattern.compile("\"([^\"]+)\"");
                    Matcher tipMatcher = tipPattern.matcher(tipsStr);
                    while (tipMatcher.find() && tips.size() < 3) {
                        tips.add(tipMatcher.group(1));
                    }
                    result.put("topTips", tips);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing AI response as JSON, using fallback parsing", e);
        }
        
        // Fallback: extract information from text if JSON parsing failed
        if (!result.containsKey("matchPercentage")) {
            Pattern percentPattern = Pattern.compile("(\\d+)%|(\\d+)\\s*percent|match[:\s]*(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = percentPattern.matcher(aiResponse);
            if (matcher.find()) {
                String percentStr = matcher.group(1) != null ? matcher.group(1) : 
                                   (matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
                result.put("matchPercentage", Integer.parseInt(percentStr));
            } else {
                result.put("matchPercentage", 0);
            }
        }
        
        if (!result.containsKey("topTips")) {
            List<String> tips = new ArrayList<>();
            // Look for numbered or bulleted tips
            String[] lines = aiResponse.split("\n");
            for (String line : lines) {
                if ((line.matches(".*\\d+[.)]\\s+.+") || line.matches(".*[-•*]\\s+.+") || 
                     line.toLowerCase().contains("tip") || line.toLowerCase().contains("suggestion")) &&
                    line.length() > 20 && tips.size() < 3) {
                    String tip = line.replaceAll("^[\\d\\s\\-•*.)]+", "").trim();
                    if (tip.length() > 10) {
                        tips.add(tip);
                    }
                }
            }
            if (tips.isEmpty()) {
                tips.add("Review the full analysis for detailed recommendations.");
            }
            result.put("topTips", tips);
        }
        
        return result;
    }
}
```
