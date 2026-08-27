Viewed pom.xml:1-125
Listed directory user

Here is a step-by-step guide to creating the `users` table entity for your modular monolith following industry standards. As requested, I'll provide the exact code you need without altering your files.

Since you are building a modular monolith, we will keep these files strictly inside the `user` module (`com.tasked.modular.user`).

### Step 1: Create the Role Enum
Instead of hardcoding strings, the industry standard is to use an Enum in your code and save it as a string in the database. 

Create a `enums` package inside your user module and add the `Role` enum:

```java
// Path: src/main/java/com/tasked/modular/user/enums/Role.java
package com.tasked.modular.user.enums;

public enum Role {
    USER,
    ADMIN,
    MODERATOR
}
```

### Step 2: Create the User Entity
We will use modern JPA/Hibernate annotations. For the UUID, since you are on a very recent Spring Boot version, `@GeneratedValue(strategy = GenerationType.UUID)` will handle the automatic UUID generation perfectly without you explicitly writing generation logic.

For `created_at` and `updated_at`, we use Spring Data JPA's built-in auditing (`@CreatedDate`, `@LastModifiedDate`).

Create an `entity` package and add the `User` class:

```java
// Path: src/main/java/com/tasked/modular/user/entity/User.java
package com.tasked.modular.user.entity;

import com.tasked.modular.user.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users") // Explicitly naming the table 'users'
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class) // Required for created_at/updated_at to work automatically
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password", nullable = false, columnDefinition = "TEXT")
    private String password;

    @Enumerated(EnumType.STRING) // Saves "USER" instead of 0 in the database
    @Column(name = "role", nullable = false)
    @Builder.Default
    private Role role = Role.USER; // Default value

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken; // Nullable by default

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```
*(Note: I used `@Builder.Default` on the role so that if you use Lombok's builder to create a user, it still defaults to `USER` if you don't explicitly set it).*

Here is a breakdown of what each of those annotations is doing. They can be split into two categories: **JPA (Database) annotations** and **Lombok (Boilerplate) annotations**.

### 1. JPA & Spring Data Annotations

These annotations tell Spring Boot how to map your Java class to your PostgreSQL database.

* **`@Entity`:** This is the core JPA annotation. It tells Hibernate (Spring Data's underlying provider) that this class represents a database record and should be managed by the entity manager.
* **`@Table(name = "users")`:** This explicitly tells Hibernate to name the database table `users` instead of the default `user`. This is **critical** in PostgreSQL because `user` is a reserved SQL keyword. If you didn't have this, your app would likely crash when trying to create the table.
* **`@EntityListeners(AuditingEntityListener.class)`:** This enables Spring Data's auditing mechanism for this specific entity. When you save a new `User`, this listener intercepts the save operation and automatically populates the `@CreatedDate` field with the current timestamp. When you update a `User`, it automatically updates the `@LastModifiedDate` field.

### 2. Lombok Annotations

These annotations are processed at compile-time to automatically write tedious Java code for you.

* **`@Getter` & `@Setter`:** Automatically generates `getEmail()`, `setPassword()`, etc., for every field in your class.
* **`@NoArgsConstructor`:** Generates an empty constructor (e.g., `public User() {}`). **This is mandatory for JPA.** Hibernate uses reflection to build entity objects from the database, and it requires an empty constructor to do so.
* **`@AllArgsConstructor`:** Generates a constructor that accepts every single field in the class. You rarely use this directly, but it is required by the `@Builder` annotation to function correctly.
* **`@Builder`:** Implements the Builder Design Pattern. It allows you to create instances of `User` using a clean, readable, chainable syntax rather than dealing with massive constructors or chaining setters.
* *Example usage:* `User.builder().email("a@b.com").password("hash").role(Role.USER).build();`

### Step 3: Create the Repository
To interact with the database, create a `repository` package and add the interface:

```java
// Path: src/main/java/com/tasked/modular/user/repository/UserRepository.java
package com.tasked.modular.user.repository;

import com.tasked.modular.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    
    // You will almost certainly need this for authentication
    Optional<User> findByEmail(String email);
    
    Optional<User> findByRefreshToken(String refreshToken);
}
```

### Step 4: Enable JPA Auditing (Crucial for timestamps)
For `@CreatedDate` and `@LastModifiedDate` to actually work automatically, you **must** enable JPA Auditing in your Spring Boot application. 

You can add `@EnableJpaAuditing` to your main application class:

```java
// Path: src/main/java/com/tasked/modular/ModularApplication.java
package com.tasked.modular;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing; // Add this

@SpringBootApplication
@EnableJpaAuditing // Add this annotation
public class ModularApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModularApplication.class, args);
    }
}
```

### Step 5: Database Creation (Industry Standard Note)
In your `application.properties` or `application.yml`, you might have `spring.jpa.hibernate.ddl-auto=update` turned on. If you run the app, Hibernate will automatically create this exact table in PostgreSQL.

**Industry Standard Tip:** While `ddl-auto=update` is fine for local prototyping, the industry standard for production modular monoliths is to disable it (`spring.jpa.hibernate.ddl-auto=validate` or `none`) and use a migration tool like **Flyway** or **Liquibase**. 
If you decide to use Flyway later, the SQL to create this exact table would look like this:

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password TEXT NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'USER',
    refresh_token TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);
```

---

Here is a detailed explanation of what the Repository does and how those specific lines work in Spring Boot.

### 1. What does a Repository do?
In modern backend architecture, the **Repository** is the layer that sits directly between your application (the Java code) and your database (PostgreSQL). Its only job is to handle data access—saving, updating, deleting, and fetching records.

By extending `JpaRepository<User, UUID>`, you are performing a bit of "Spring Magic." 
* `User` tells Spring that this repository manages the `users` table.
* `UUID` tells Spring that the primary key of this table is a UUID.

**Why is this powerful?**
Without writing a single line of SQL or implementation code, Spring automatically generates all standard database operations for you in the background. If you inject `UserRepository` into a service, you instantly get access to methods like:
* `userRepository.save(user)` (Inserts or updates a user)
* `userRepository.findById(uuid)` (Selects a user by their ID)
* `userRepository.findAll()` (Selects all users)
* `userRepository.deleteById(uuid)` (Deletes a user)

### 2. What to do when you add another table?
In JPA, there is a strict **1-to-1 relationship between a Table, an Entity, and a Repository**. 

If you add a new table to your database tomorrow, for example, a `posts` table, you will follow the exact same pattern:
1. Create a `Post` Entity class (`@Entity`, `@Table(name = "posts")`).
2. Create a brand new `PostRepository` interface that extends `JpaRepository<Post, Long>` (assuming the post ID is a Long/BigInt).

You **never** mix database operations for different tables in the same repository. `UserRepository` is strictly for the `users` table.

---

### 3. Explaining the two custom lines

```java
Optional<User> findByEmail(String email);
Optional<User> findByRefreshToken(String refreshToken);
```

While `JpaRepository` gives you basic tools like `findById`, it doesn't know about your specific columns like `email` or `refresh_token`. If you want to search by those columns, you use a feature called **Query Methods**.

#### How the naming works
Spring Data JPA looks at the name of your method. Because it starts with `findBy` and is followed by the exact name of your entity property (`Email` or `RefreshToken`), Spring automatically writes the SQL for you at application startup!

* `findByEmail(String email)` automatically generates and executes this SQL under the hood: 
  `SELECT * FROM users WHERE email = ?`
* `findByRefreshToken(String refreshToken)` generates: 
  `SELECT * FROM users WHERE refresh_token = ?`

#### Why wrap it in `Optional<User>`?
When you search for an email in the database, there are two possibilities:
1. The user is found.
2. The user is NOT found (e.g., they typed the wrong email).

In older Java versions, if the user wasn't found, the method would return `null`, which often caused application-crashing `NullPointerExceptions`. 

`Optional<User>` is a safety box. It forces whoever calls this method to explicitly check if the user is inside the box before trying to use it. 
For example, in your login service, you will write something like:
```java
var userOptional = userRepository.findByEmail("test@example.com");

if (userOptional.isPresent()) {
    // Check passwords and log them in
} else {
    // Throw an "Invalid credentials" error
}
```

#### Why are these specific methods needed?
* **`findByEmail`**: When a user tries to log in, they provide their email and password. You need this method to fetch the user from the database by their email so you can verify if the password matches.
* **`findByRefreshToken`**: After a user logs in, they are given an Access Token (short-lived, e.g., 15 minutes) and a Refresh Token (long-lived, e.g., 7 days). When the access token expires, the client sends the refresh token to get a new one. You need this method to look up who owns that refresh token in the database to issue them new credentials.

---

The actual ORM (Object-Relational Mapping) framework being used here is **Hibernate**.

However, in a Spring Boot application, there are three layers working together that developers often confuse. Here is how they stack up in your project:

### 1. Hibernate (The ORM)
Hibernate is the engine under the hood doing the actual work. It is the ORM that translates your Java `User` object into the PostgreSQL `users` table, and translates the Java method calls into raw SQL queries (like `INSERT INTO...` or `SELECT...`).

### 2. JPA / Jakarta Persistence API (The Standard)
JPA is not an ORM itself; it is a set of rules and a specification defined by Java. 
* All the annotations we used on the entity (`@Entity`, `@Table`, `@Id`, `@Column`) belong to JPA, not Hibernate.
* Hibernate is just the tool that **implements** the JPA standard.
* *Why use JPA?* Because it standardizes your code. If you ever wanted to rip out Hibernate and replace it with another ORM (like EclipseLink), you wouldn't have to change your `@Entity` code at all.

### 3. Spring Data JPA (The Abstraction)
This is what provides the `JpaRepository` interface you just looked at. 
* Spring Data JPA is a layer built on top of JPA (which is powered by Hibernate). 
* Its entire purpose is to save you from writing boilerplate code. Instead of manually writing an `EntityManager` to execute Hibernate queries, Spring Data JPA lets you just write an interface and it writes the implementation for you dynamically.

**Summary:** 
When you call `userRepository.findByEmail()`, **Spring Data JPA** intercepts the call, passes it to the **JPA** standard interface, which then tells the **Hibernate ORM** to generate the SQL and fetch the data from PostgreSQL.