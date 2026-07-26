"""知识会话对外契约。"""

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class KnowledgeMode(StrEnum):
    AUTO = "AUTO"
    LOCAL_ONLY = "LOCAL_ONLY"


class WebSearchPolicy(StrEnum):
    AUTO = "AUTO"
    ENABLED = "ENABLED"
    DISABLED = "DISABLED"


class KnowledgeCitation(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    source_type: str
    title: str
    snippet: str
    material_id: str | None = None
    locator: str | None = None
    result_id: str | None = None
    url: str | None = None


class KnowledgeConversationSnapshot(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    conversation_id: str
    owner_id: str
    mode: KnowledgeMode
    status: str = "ACTIVE"
    answer: str = ""
    retrieval_mode: str
    citations: list[KnowledgeCitation] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class CreateKnowledgeConversationRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    owner_id: str = Field(min_length=1)
    mode: KnowledgeMode = KnowledgeMode.AUTO


class SendKnowledgeMessageRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    message: str = Field(min_length=1, max_length=10_000)
    web_search: WebSearchPolicy = WebSearchPolicy.AUTO
