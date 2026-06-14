# 第 1 轮：初始方案设计

> **操作说明**：将下面三个 Prompt 分别复制到对应的 AI 对话框中，提交后收集回答。

---

## Prompt for Gemini

```
You are an expert software architect with deep experience in Java, browser automation, and multi-agent systems.

Please design a comprehensive implementation plan for the following project:

=== PROJECT: ai-debate-arena ===

Build a Java application that:
1. Uses Playwright (Java) for browser automation — NO API keys, pure browser interaction
2. Simultaneously controls three AI platforms: Gemini (gemini.google.com), ChatGPT (chatgpt.com), DeepSeek (chat.deepseek.com)
3. Orchestrates a multi-round debate among them:
   - Round 1: All 3 AIs answer the same technical question
   - Round 2: Each AI critiques the other two's answers
   - Round 3+: Rebuttals and convergence checks until consensus or max rounds
4. Handles persistent browser profiles so users don't re-login every time
5. Exposes REST API (Spring Boot) to start/query debates
6. Outputs a structured Markdown report as the final deliverable

Tech stack constraints:
- Java 17
- Maven
- Spring Boot 3.2.5
- Spring AI (for prompt templates only, NOT for API calls)
- Playwright 1.54.0 for Java
- The app runs as a local CLI/server, not cloud-deployed

Please structure your response with the following sections:

## 1. Architecture Design
- Overall system architecture diagram (ASCII art)
- Component responsibilities and interactions
- Data flow through the system

## 2. Project Structure
- Package layout
- Key classes and interfaces
- Dependency management (pom.xml highlights)

## 3. Core Implementation Strategy
- How to handle browser automation for each AI platform (adapter pattern)
- How to manage persistent browser profiles (Chromium userDataDir)
- How to orchestrate the debate rounds (state machine / workflow engine)
- How to detect convergence (NLP similarity approaches)

## 4. Risk Analysis
- What are the biggest technical risks?
- How do you handle AI platform UI changes breaking selectors?
- How do you handle platform rate-limiting or bot detection?
- What happens when one platform fails mid-debate?

## 5. Implementation Roadmap
- Development phases (in order)
- Which parts to build first
- Estimated complexity for each phase

Please be specific, concrete, and actionable. This plan will be critiqued by two other AI experts (ChatGPT and DeepSeek), and your response will be compared with theirs.
```

### ⬇️ Gemini 回答（复制粘贴到这里）

见[01-1-gemini回答.md](./01-1-gemini回答.md)文档

---

## Prompt for ChatGPT

```
You are an expert software architect with deep experience in Java, browser automation, and multi-agent systems.

Please design a comprehensive implementation plan for the following project:

=== PROJECT: ai-debate-arena ===

Build a Java application that:
1. Uses Playwright (Java) for browser automation — NO API keys, pure browser interaction
2. Simultaneously controls three AI platforms: Gemini (gemini.google.com), ChatGPT (chatgpt.com), DeepSeek (chat.deepseek.com)
3. Orchestrates a multi-round debate among them:
   - Round 1: All 3 AIs answer the same technical question
   - Round 2: Each AI critiques the other two's answers
   - Round 3+: Rebuttals and convergence checks until consensus or max rounds
4. Handles persistent browser profiles so users don't re-login every time
5. Exposes REST API (Spring Boot) to start/query debates
6. Outputs a structured Markdown report as the final deliverable

Tech stack constraints:
- Java 17
- Maven
- Spring Boot 3.2.5
- Spring AI (for prompt templates only, NOT for API calls)
- Playwright 1.54.0 for Java
- The app runs as a local CLI/server, not cloud-deployed

Please structure your response with the following sections:

## 1. Architecture Design
- Overall system architecture diagram (ASCII art)
- Component responsibilities and interactions
- Data flow through the system

## 2. Project Structure
- Package layout
- Key classes and interfaces
- Dependency management (pom.xml highlights)

## 3. Core Implementation Strategy
- How to handle browser automation for each AI platform (adapter pattern)
- How to manage persistent browser profiles (Chromium userDataDir)
- How to orchestrate the debate rounds (state machine / workflow engine)
- How to detect convergence (NLP similarity approaches)

## 4. Risk Analysis
- What are the biggest technical risks?
- How do you handle AI platform UI changes breaking selectors?
- How do you handle platform rate-limiting or bot detection?
- What happens when one platform fails mid-debate?

## 5. Implementation Roadmap
- Development phases (in order)
- Which parts to build first
- Estimated complexity for each phase

Please be specific, concrete, and actionable. This plan will be critiqued by DeepSeek and Gemini, and your response will be compared with theirs.
```

### ⬇️ ChatGPT 回答（复制粘贴到这里）

见[01-1-ChatGPT回答.md](./01-1-ChatGPT回答.md)文档

---

## Prompt for DeepSeek

```
You are an expert software architect with deep experience in Java, browser automation, and multi-agent systems.

Please design a comprehensive implementation plan for the following project:

=== PROJECT: ai-debate-arena ===

Build a Java application that:
1. Uses Playwright (Java) for browser automation — NO API keys, pure browser interaction
2. Simultaneously controls three AI platforms: Gemini (gemini.google.com), ChatGPT (chatgpt.com), DeepSeek (chat.deepseek.com)
3. Orchestrates a multi-round debate among them:
   - Round 1: All 3 AIs answer the same technical question
   - Round 2: Each AI critiques the other two's answers
   - Round 3+: Rebuttals and convergence checks until consensus or max rounds
4. Handles persistent browser profiles so users don't re-login every time
5. Exposes REST API (Spring Boot) to start/query debates
6. Outputs a structured Markdown report as the final deliverable

Tech stack constraints:
- Java 17
- Maven
- Spring Boot 3.2.5
- Spring AI (for prompt templates only, NOT for API calls)
- Playwright 1.54.0 for Java
- The app runs as a local CLI/server, not cloud-deployed

Please structure your response with the following sections:

## 1. Architecture Design
- Overall system architecture diagram (ASCII art)
- Component responsibilities and interactions
- Data flow through the system

## 2. Project Structure
- Package layout
- Key classes and interfaces
- Dependency management (pom.xml highlights)

## 3. Core Implementation Strategy
- How to handle browser automation for each AI platform (adapter pattern)
- How to manage persistent browser profiles (Chromium userDataDir)
- How to orchestrate the debate rounds (state machine / workflow engine)
- How to detect convergence (NLP similarity approaches)

## 4. Risk Analysis
- What are the biggest technical risks?
- How do you handle AI platform UI changes breaking selectors?
- How do you handle platform rate-limiting or bot detection?
- What happens when one platform fails mid-debate?

## 5. Implementation Roadmap
- Development phases (in order)
- Which parts to build first
- Estimated complexity for each phase

Please be specific, concrete, and actionable. This plan will be critiqued by ChatGPT and Gemini, and your response will be compared with theirs.
```

### ⬇️ DeepSeek 回答（复制粘贴到这里）

见[01-1-DeepSeek回答.md](./01-1-DeepSeek回答.md)文档

---

## 第 1 轮完成检查清单

- [ ] Gemini 回答已粘贴到上方
- [ ] ChatGPT 回答已粘贴到上方
- [ ] DeepSeek 回答已粘贴到上方
- [ ] 三个回答已保存，准备进入第 2 轮
