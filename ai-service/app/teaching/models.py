"""课时内 AI 导师的请求、快照和结构化模型输出。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.knowledge.models import KnowledgeCitation

TeachingAction = Literal[
    "CHECK_UNDERSTANDING",
    "SHOW_EXAMPLE",
    "GIVE_HINT",
    "CONTINUE_LESSON",
]


class TeachingAnswer(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    answer: str = Field(min_length=1)
    citations: list[KnowledgeCitation] = Field(default_factory=list)
    suggested_actions: list[TeachingAction] = Field(default_factory=list)


class TeachingConversationSnapshot(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    conversation_id: str
    owner_id: str
    lesson_id: str
    status: str = "ACTIVE"
    answer: str = ""
    citations: list[KnowledgeCitation] = Field(default_factory=list)
    suggested_actions: list[TeachingAction] = Field(default_factory=list)
    model_provider: str
    model_name: str


class CreateTeachingConversationRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    owner_id: str = Field(min_length=1)
    lesson_id: str = Field(min_length=1, max_length=200)


class SendTeachingMessageRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    owner_id: str = Field(min_length=1)
    message: str = Field(min_length=1, max_length=10_000)


class TeachingTurn(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    answer: str = Field(min_length=1)
    cited_source_indexes: list[int] = Field(default_factory=list)
    suggested_actions: list[TeachingAction] = Field(default_factory=list)
