"""Structured output contract for artifact rubric evaluation."""

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ArtifactReviewFile(BaseModel):
    path: str = Field(min_length=1, max_length=1024)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    content: str = Field(max_length=131_072)


class RunnerEvidence(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    execution_id: str = Field(alias="executionId", min_length=1, max_length=36)
    template_type: str = Field(alias="templateType", min_length=1, max_length=40)
    success: bool
    summary: str = Field(max_length=4000)


class ArtifactReviewRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    owner_id: str = Field(alias="ownerId", min_length=1, max_length=36)
    artifact_id: str = Field(alias="artifactId", min_length=1, max_length=36)
    description: str = Field(min_length=1, max_length=2000)
    runner_evidence: RunnerEvidence = Field(alias="runnerEvidence")
    files: list[ArtifactReviewFile] = Field(min_length=1, max_length=30)


class ArtifactRubricResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    functional_correctness: int = Field(alias="functionalCorrectness", ge=0, le=40)
    requirements_completeness: int = Field(alias="requirementsCompleteness", ge=0, le=25)
    test_quality: int = Field(alias="testQuality", ge=0, le=20)
    code_quality: int = Field(alias="codeQuality", ge=0, le=15)
    score: int = Field(ge=0, le=100)
    feedback: str = Field(min_length=1, max_length=4000)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    issues: list[str] = Field(default_factory=list, max_length=10)

    @model_validator(mode="after")
    def score_matches_breakdown(self) -> "ArtifactRubricResult":
        total = (
            self.functional_correctness
            + self.requirements_completeness
            + self.test_quality
            + self.code_quality
        )
        if self.score != total:
            raise ValueError("score must equal the four rubric components")
        return self
