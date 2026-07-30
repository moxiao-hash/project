package com.moxiao.studypilot.course.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.course.application.CourseLearningService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseControllerTest {

    @Test
    void continueLearningReturnsNoContentWhenEveryPublishedLessonIsComplete() {
        CourseLearningService service = mock(CourseLearningService.class);
        when(service.continueLearning("user-1")).thenReturn(null);

        var response = new CourseController(service).continueLearning(
                new AuthenticatedUser("user-1", "user@example.com", "User")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
