**Build Tools (Project)**

* **Maven:** Uses an XML file (`pom.xml`). It is the standard Java build tool with strict conventions, extreme stability, and universal support across corporate environments.
* **Gradle - Groovy:** Uses a Groovy-based scripting syntax (`build.gradle`). It builds faster than Maven due to incremental caching and flexible build logic.
* **Gradle - Kotlin:** Uses Kotlin (`build.gradle.kts`) for build scripts. It offers superior IDE auto-completion, compile-time type-checking, and easier refactoring than Groovy.

**Packaging**

* **JAR (Java Archive):** Bundles your compiled application along with an embedded web server (Tomcat) into a single executable file (`java -jar app.jar`). This is the modern default for microservices and cloud deployments.
* **WAR (Web Application Archive):** Designed to be deployed onto a separate, externally managed application server (such as WildFly or external Tomcat). Primarily used in legacy enterprise infrastructure.

**Configuration Formats**

* **Properties (`.properties`):** Uses a flat `key=value` structure (`spring.datasource.url=...`). Simple, but requires repeating prefixes for nested options.
* **YAML (`.yaml`):** Uses structured indentation. It eliminates repetitive prefixes, making complex configurations much cleaner to manage.

**Core Dependencies**

* **Spring Web:** The core library for REST APIs. Includes Spring MVC, automatic JSON translation, and an embedded web server.
* **Spring Data JPA:** Uses Hibernate behind the scenes to map Java classes (`@Entity`) directly to database tables, handling database operations without writing manual SQL queries.
* **PostgreSQL Driver:** The essential database connector that allows Spring Boot's JDBC framework to communicate with your PostgreSQL server.
* **Validation:** Provides data guardrails (like `@NotNull`, `@Size`, `@Email`) to automatically validate incoming API request bodies before processing them in your service layer.
* **Lombok:** A compile-time annotation processor that automatically generates getters, setters, constructors, and builders, keeping your Java files short and clean.

---

The combination of `@RequiredArgsConstructor` and the `final` keyword is the modern, industry-standard way to perform **Constructor Dependency Injection** in Spring Boot. It replaces the older method of using `@Autowired` on fields.

Here is exactly how they work together:

### 1. The `final` keyword (The Requirement)

In Java, when you declare a variable as `final`, it means it **must** be initialized exactly once (either right away or inside a constructor), and it can never be changed or reassigned afterward.

By making `userService` final, you are ensuring two things:

* **Safety:** The dependency can never be accidentally changed to a different object or set to `null` while the application is running, making your controller completely thread-safe.
* **Mandatory Initialization:** Java will force you to provide a `UserService` when this controller is created.

### 2. `@RequiredArgsConstructor` (The Magic)

Since `userService` is `final`, Java requires a constructor to initialize it. Instead of writing that constructor yourself, you add Lombok's `@RequiredArgsConstructor`.

When you compile your code, Lombok automatically generates a constructor that requires an argument for every `final` field in your class.

### What it looks like under the hood

When Spring Boot starts up, it looks at your class. Even though you didn't write a constructor, Lombok generated this one for you behind the scenes:

```java
// Lombok generates this invisible code during compilation:
public class UserController {
    
    private final UserService userService;

    // Spring Boot sees this constructor and injects the UserService here!
    public UserController(UserService userService) {
        this.userService = userService;
    }
    
    // ... your endpoints ...
}

```

Because Spring Boot automatically injects dependencies if a class only has one constructor, it seamlessly provides the `UserService` implementation to your controller. This keeps your code incredibly clean and free of boilerplate!

---

This class is the **entry point** for your entire Spring Boot application. Whenever you click "Run," this is exactly where Java starts executing.

Here is a breakdown of what the two main parts are doing:

### 1. The `@SpringBootApplication` Annotation

This is the most important annotation in Spring Boot. It is actually a "3-in-1" convenience annotation that does the following behind the scenes:

* **`@EnableAutoConfiguration`:** It looks at your `pom.xml` dependencies and automatically configures your app. For example, because it sees the `PostgreSQL Driver` and `Spring Web`, it automatically sets up a database connection pool and an embedded web server for you.
* **`@ComponentScan`:** It tells Spring to scan the `com.tasked.modular` package and all of its sub-folders (like your `user` module) to find your `@RestController`, `@Service`, and `@Repository` classes and load them into memory.
* **`@Configuration`:** It marks this class as a source of configuration, meaning you can define extra setup instructions here if needed.

### 2. The `main` Method

Just like any standard Java program, Spring Boot needs a `main` method to run.

* **`SpringApplication.run(...)`:** This single line does all the heavy lifting. It launches the Spring framework, creates the application context (which wires your controllers and services together), and starts the embedded Tomcat web server (usually on port 8080) so you can start receiving HTTP requests.

In short, this class is the engine that breathes life into your code and turns it into a running web server!

---

The `pom.xml` (Project Object Model) is the most important configuration file in a Maven-based Java project. Think of it as the **blueprint or recipe** for your application. It tells Maven exactly how to build your project, what version of Java to compile with, and which external libraries to download.

Here is a breakdown of its major sections:

### 1. The Parent (`<parent>`)

This block points to the `spring-boot-starter-parent`. It is a master configuration provided by Spring. It acts as an umbrella that manages library versions for you. Because of this parent, you do not have to guess which version of Hibernate works with which version of Tomcat—Spring guarantees they are perfectly compatible, saving you from endless version conflicts.

### 2. Project Metadata

This section defines the unique identity of your application.

* **`<groupId>`:** Your organization or reverse domain name (e.g., `com.tasked`).
* **`<artifactId>`:** The specific name of your project (e.g., `modular`).
* **`<version>`:** The current release version of your code (e.g., `0.0.1-SNAPSHOT`).
* **`<packaging>`:** Defines the output format, usually `jar` (Java Archive).

### 3. Properties (`<properties>`)

This area holds global variables for the build process. The most important one here is `<java.version>`, which tells Maven to compile the code strictly for the Java version you specified earlier (like Java 25).

### 4. Dependencies (`<dependencies>`)

This is your shopping list of tools. Whenever you need a new framework or library, you paste its dependency block here. Maven automatically connects to the internet (Maven Central Repository), downloads the required files, and links them to your code.

* **`spring-boot-starter-web`:** Brings in the Tomcat server and REST API classes.
* **`postgresql`:** Brings in the driver to talk to your local database.
* **`lombok`:** Brings in the boilerplate-reduction tool we used earlier.

### 5. Build & Plugins (`<build>`)

This section instructs Maven on *how* to construct your final application. It contains the `spring-boot-maven-plugin`, which takes all your compiled code, plus all the dependencies you downloaded, and bundles them together into a single, executable file that you can deploy to a server.

---