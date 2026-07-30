from app.study_scope import build_learning_web_query


def test_tutorial_query_prioritizes_itheima() -> None:
    assert build_learning_web_query("推荐 Spring Boot 学习视频").startswith(
        "黑马程序员 itheima B站"
    )


def test_version_query_is_not_rewritten_as_a_tutorial_query() -> None:
    query = "Spring Boot 当前支持哪个 Java 版本？"

    assert build_learning_web_query(query) == query
