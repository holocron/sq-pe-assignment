-- V9: a third model role on llm_settings.
--
-- Until now the single chat_model drove every chat call: the orchestrator's closing summary, the
-- rule judge, the Enhance wand AND every rule subagent's ReAct mini-loop. Those are two different
-- workloads - one deep reasoning model, and a (possibly smaller, faster) model that only needs
-- reliable tool calling - so the subagents get their own tool_model column.
--
-- Existing rows copy chat_model: that is exactly what the subagents ran on before this migration,
-- so it is the only lossless default. The tooling model shares the chat credential (chat_api_key);
-- no third key column is added.
ALTER TABLE llm_settings
    ADD COLUMN tool_model TEXT;

UPDATE llm_settings
SET tool_model = chat_model;

ALTER TABLE llm_settings ALTER COLUMN tool_model SET NOT NULL;
