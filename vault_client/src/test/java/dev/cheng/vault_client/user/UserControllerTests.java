package dev.cheng.vault_client.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Test
    void listsMigratedSampleUsers() throws Exception {
        mvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem("zhangsan@example.com")))
                .andExpect(jsonPath("$[*].email", hasItem("lisi@example.com")))
                .andExpect(jsonPath("$[*].email", hasItem("wangwu@example.com")));
    }

    @Test
    void getsUserById() throws Exception {
        User user = users.findAll().stream()
                .filter(candidate -> candidate.getEmail().equals("zhangsan@example.com"))
                .findFirst().orElseThrow();

        mvc.perform(get("/api/users/{id}", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.name").value("张三"))
                .andExpect(jsonPath("$.email").value("zhangsan@example.com"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void returnsNotFoundForMissingUser() throws Exception {
        mvc.perform(get("/api/users/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }
}
