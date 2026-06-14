# 第 2 轮：交叉批判

> **操作说明**：
> 1. 先将第 1 轮各 AI 的实际回答填入下方 `{expert_X_response}` 占位符
> 2. 将填好后的 Prompt 分别复制到对应 AI 对话框
> 3. 收集批判结果

---

## 2a. 发给 Gemini（让它批判 ChatGPT 和 DeepSeek 的方案）

将下方内容填入后复制到 Gemini：

```
You are participating in a multi-expert technical debate. Two other experts
(ChatGPT and DeepSeek) have provided their implementation plans for the
"ai-debate-arena" project. Please critique each one.

=== TOPIC ===
How to design and implement ai-debate-arena: a Java application using Playwright
browser automation to orchestrate multi-AI debates among Gemini, ChatGPT, and DeepSeek
=============

=== ChatGPT's RESPONSE ===
[【在此粘贴 ChatGPT 在第 1 轮的回答】]
===========================

=== DeepSeek's RESPONSE ===
[【在此粘贴 DeepSeek 在第 1 轮的回答】]
============================

=== YOUR PREVIOUS POSITION (for reference) ===
[【在此粘贴 Gemini 自己在第 1 轮的回答】]
============================================

---

Please provide a detailed critique with the following sections:

## Critique of ChatGPT's Plan

### Points of Agreement
Which parts of ChatGPT's architecture/design do you agree with? Why?

### Points of Disagreement
Which aspects of ChatGPT's plan do you disagree with? Be specific — reference
exact claims or design choices and explain why they are wrong or suboptimal.

### Omissions
What important considerations did ChatGPT miss that should be part of the plan?

### Quality Assessment
Rate ChatGPT's plan on:
- Technical Accuracy (Strong / Adequate / Weak)
- Completeness (Strong / Adequate / Weak)
- Practicality (Strong / Adequate / Weak)
(One-sentence justification for each)

---

## Critique of DeepSeek's Plan

### Points of Agreement
### Points of Disagreement
### Omissions
### Quality Assessment

---

## Position Update

After reviewing both experts' plans:
- Has your own design changed? If so, what specific changes would you make?
- What is the single best idea from each expert that you would incorporate?
- What is your updated overall recommendation for implementing this project?
```

---

## 2b. 发给 ChatGPT（让它批判 Gemini 和 DeepSeek 的方案）

将下方内容填入后复制到 ChatGPT：

```
You are participating in a multi-expert technical debate. Two other experts
(Gemini and DeepSeek) have provided their implementation plans for the
"ai-debate-arena" project. Please critique each one.

=== TOPIC ===
How to design and implement ai-debate-arena: a Java application using Playwright
browser automation to orchestrate multi-AI debates among Gemini, ChatGPT, and DeepSeek
=============

=== Gemini's RESPONSE ===
[【在此粘贴 Gemini 在第 1 轮的回答】]
=========================

=== DeepSeek's RESPONSE ===
[【在此粘贴 DeepSeek 在第 1 轮的回答】]
============================

=== YOUR PREVIOUS POSITION (for reference) ===
[【在此粘贴 ChatGPT 自己在第 1 轮的回答】]
============================================

---

Please provide a detailed critique with the following sections:

## Critique of Gemini's Plan

### Points of Agreement
Which parts of Gemini's architecture/design do you agree with? Why?

### Points of Disagreement
Which aspects of Gemini's plan do you disagree with? Be specific — reference
exact claims or design choices and explain why they are wrong or suboptimal.

### Omissions
What important considerations did Gemini miss that should be part of the plan?

### Quality Assessment
Rate Gemini's plan on:
- Technical Accuracy (Strong / Adequate / Weak)
- Completeness (Strong / Adequate / Weak)
- Practicality (Strong / Adequate / Weak)
(One-sentence justification for each)

---

## Critique of DeepSeek's Plan

### Points of Agreement
### Points of Disagreement
### Omissions
### Quality Assessment

---

## Position Update

After reviewing both experts' plans:
- Has your own design changed? If so, what specific changes would you make?
- What is the single best idea from each expert that you would incorporate?
- What is your updated overall recommendation for implementing this project?
```

---

## 2c. 发给 DeepSeek（让它批判 Gemini 和 ChatGPT 的方案）

将下方内容填入后复制到 DeepSeek：

```
You are participating in a multi-expert technical debate. Two other experts
(Gemini and ChatGPT) have provided their implementation plans for the
"ai-debate-arena" project. Please critique each one.

=== TOPIC ===
How to design and implement ai-debate-arena: a Java application using Playwright
browser automation to orchestrate multi-AI debates among Gemini, ChatGPT, and DeepSeek
=============

=== Gemini's RESPONSE ===
[【在此粘贴 Gemini 在第 1 轮的回答】]
=========================

=== ChatGPT's RESPONSE ===
[【在此粘贴 ChatGPT 在第 1 轮的回答】]
==========================

=== YOUR PREVIOUS POSITION (for reference) ===
[【在此粘贴 DeepSeek 自己在第 1 轮的回答】]
=============================================

---

Please provide a detailed critique with the following sections:

## Critique of Gemini's Plan

### Points of Agreement
Which parts of Gemini's architecture/design do you agree with? Why?

### Points of Disagreement
Which aspects of Gemini's plan do you disagree with? Be specific — reference
exact claims or design choices and explain why they are wrong or suboptimal.

### Omissions
What important considerations did Gemini miss that should be part of the plan?

### Quality Assessment
Rate Gemini's plan on:
- Technical Accuracy (Strong / Adequate / Weak)
- Completeness (Strong / Adequate / Weak)
- Practicality (Strong / Adequate / Weak)
(One-sentence justification for each)

---

## Critique of ChatGPT's Plan

### Points of Agreement
### Points of Disagreement
### Omissions
### Quality Assessment

---

## Position Update

After reviewing both experts' plans:
- Has your own design changed? If so, what specific changes would you make?
- What is the single best idea from each expert that you would incorporate?
- What is your updated overall recommendation for implementing this project?
```

---

## 第 2 轮完成检查清单

- [ ] Gemini 的批判回答已收集
- [ ] ChatGPT 的批判回答已收集
- [ ] DeepSeek 的批判回答已收集
- [ ] 准备进入第 3 轮
