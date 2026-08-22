## 1. The Project Structure (Package-by-Feature)

The most common approach for Spring Boot is a single Maven/Gradle project where strict modularity is enforced at the package level.

```text
src/main/java/com/yourcompany/ecommerce
│
├── EcommerceApplication.java        # Spring Boot Main Class
│
├── shared/                          # Cross-cutting concerns (used by all modules)
│   ├── exception/                   # Global exception handlers (@ControllerAdvice)
│   ├── config/                      # Global security, Swagger, Postgres DB configs
│   └── dto/                         # Common base DTOs or standard API responses
│
├── user/                            # MODULE 1: User Domain
│   ├── internal/                    # (Optional) Hides implementation details
│   │   ├── UserController.java
│   │   ├── UserService.java
│   │   ├── UserEntity.java
│   │   └── UserRepository.java
│   ├── dto/                         # Exposed DTOs
│   └── UserAPI.java                 # Public interface for other modules to use
│
├── order/                           # MODULE 2: Order Domain
│   ├── controller/                  # REST endpoints (OrderController)
│   ├── service/                     # Business logic (OrderService)
│   ├── entity/                      # JPA Entities (Order, OrderItem)
│   ├── repository/                  # Spring Data JPA Repositories
│   ├── dto/                         # Request/Response objects
│   └── event/                       # Domain events (OrderPlacedEvent)
│
└── inventory/                       # MODULE 3: Inventory Domain
    ├── controller/
    ├── service/
    ├── entity/
    ├── repository/
    └── dto/

```

*Note: For even stricter boundaries, you can use a **Maven/Gradle Multi-Module** setup where `user`, `order`, and `inventory` are completely separate `pom.xml` / `build.gradle` projects that get compiled into a single executable JAR.*

---

## 2. Anatomy of a Single Module

Each module should be self-contained. If you decide to extract the "Order" module into a separate microservice two years from now, you should be able to lift the `order/` package and move it without breaking the rest of the app.

* **Controllers:** Handle HTTP requests and map them to Services. They should only deal with DTOs, never raw Entities.
* **Services:** Contain the core business logic.
* **Entities (`@Entity`):** Map to your PostgreSQL tables.
* **Repositories:** Handle database operations.
* **DTOs (Data Transfer Objects):** The strict data contracts for input/output.

---

## 3. Golden Rules for Modular Monoliths

To prevent your modular monolith from degrading into a "big ball of mud," you must follow strict architectural rules:

### A. No Direct Database Joins Across Modules

Even though you are using a single PostgreSQL database, **modules must not share tables**.

* **Bad:** The `Order` entity has a `@ManyToOne` relationship to the `User` entity.
* **Good:** The `Order` entity simply stores a `userId` (Long/UUID). If the Order module needs user details, it asks the User module for them.

### B. Modules Communicate via Interfaces, Not Implementations

If the `OrderService` needs to check inventory, it should not inject the `InventoryRepository`.

* Create a public interface in the Inventory module (e.g., `InventoryFacade` or `InventoryAPI`).
* The `OrderService` injects that interface. This keeps the database logic of Inventory completely hidden from Order.

### C. Prefer Event-Driven Communication

To keep modules highly decoupled, use Spring's `@ApplicationEventPublisher`.

* When an order is created, `OrderService` publishes an `OrderCreatedEvent`.
* The `InventoryService` listens for this event (`@EventListener` or `@TransactionalEventListener`) and deducts stock.
* This means the Order module doesn't even need to know the Inventory module exists.

---

## 4. Database Schema Management (PostgreSQL)

Since modules shouldn't share database tables, it is highly recommended to use **PostgreSQL Schemas** to enforce separation at the database level.

* Create a schema for each module: `CREATE SCHEMA orders;`, `CREATE SCHEMA inventory;`
* In your JPA entities, specify the schema:
```java
@Entity
@Table(name = "order", schema = "orders")
public class Order { ... }

```

* Use a migration tool like **Flyway** or **Liquibase**, keeping migration scripts separate for each module (e.g., `src/main/resources/db/migration/orders/` and `.../inventory/`).

---

Since you are running Java 25, you should generate your project using **Spring Boot 4.x**, which introduced native, first-class support for this Java LTS release.

## 1. Required VS Code Extensions

To turn VS Code into a fully-fledged Java IDE, you only need to install a few extensions from the marketplace (press `Ctrl + Shift + X` to open the extensions tab):

* **Extension Pack for Java** (by Microsoft): This is the core engine. It provides language support, Maven/Gradle integration, intelligent code completion, and debugging capabilities.
* **Spring Boot Extension Pack** (by VMware): This adds Spring-specific features, including an embedded Spring Initializr, an application running dashboard, and auto-completion for your configuration files.
* **PostgreSQL** (by Chris Kolkman) *[Optional]*: Allows you to view your local Postgres database tables directly inside VS Code, saving you from having to keep pgAdmin or another client open.

## 2. Tech Stack & Packages (Dependencies)

When we initialize the project, you will need to select the following standard Spring Boot dependencies:

* **Spring Web:** For building your REST API controllers (Spring MVC) and embedded Tomcat server.
* **Spring Data JPA:** For your ORM logic, Repositories, and managing database Entities.
* **PostgreSQL Driver:** The JDBC driver that allows Spring to communicate with your local database.
* **Validation:** For validating incoming payloads (e.g., using `@NotNull`, `@NotBlank`).
* **Lombok** *(Recommended)*: A Java library that automatically generates getters, setters, and constructors, keeping your Entities and DTOs free of boilerplate code.

---

## 3. Step-by-Step Setup Guide

1. **Generate the Project via Spring Initializr:**
1. In VS Code, press `Ctrl + Shift + P` to open the Command Palette.
2. Type **Spring Initializr** and select `Spring Initializr: Create a Maven Project`.
3. **Boot Version:** Choose the latest **4.x.x** version (essential for Java 25 support).
4. **Language:** Select **Java**.
5. **Metadata:** Enter your Group ID (e.g., `com.yourcompany`) and Artifact ID (e.g., `modular-backend`).
6. **Packaging:** Select **Jar**.
7. **Java Version:** Select **25**. *(If 25 isn't in the dropdown yet, pick the highest available and we'll fix it in the next step).*
8. **Dependencies:** Search for and select **Spring Web**, **Spring Data JPA**, **PostgreSQL Driver**, **Validation**, and **Lombok**.
9. Press Enter, choose a folder on your laptop, and click **Open** when prompted.


2. **Verify Java 25 Configuration:**
Open the `pom.xml` file in the root of your new project. Ensure the Java version property is explicitly set to 25:

```xml
<properties>
    <java.version>25</java.version>
</properties>

```

If you make a change, VS Code will display a small pop-up asking to synchronize the Java project. Click **Yes**.


3. **Configure Local PostgreSQL:**
Navigate to `src/main/resources/application.properties` and add your local Postgres credentials. Be sure to create an empty database in Postgres first (e.g., named `modular_db`).

```properties
# Server configuration
server.port=8080

# PostgreSQL Connection
spring.datasource.url=jdbc:postgresql://localhost:5432/modular_db
spring.datasource.username=postgres
spring.datasource.password=your_super_secret_password
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA & Hibernate Settings
# 'update' automatically creates tables based on your Entities (good for dev)
spring.jpa.hibernate.ddl-auto=update 
spring.jpa.show-sql=true

```


4. **Scaffold the First Module:**
In the VS Code Explorer, navigate to `src/main/java/com/yourcompany/modularbackend/`.

Create a new folder named `user` to act as your first domain module. Inside `user`, create your modular monolith structure:

* `controller/` (e.g., `UserController.java`)
* `service/` (e.g., `UserService.java`)
* `entity/` (e.g., `User.java`)
* `repository/` (e.g., `UserRepository.java`)
* `dto/` (e.g., `UserRequest.java`)


5. **Run the Application:**
### Method 1: The VS Code UI (Easiest)

1. Open your `ModularApplication.java` file in VS Code.
2. Do **not** use the terminal to type commands.
3. Look directly at your code. Right above the line `public static void main(String[] args)`, you should see a tiny, clickable text that says **Run | Debug** (injected by the Java Extension Pack).
4. Click **Run**. VS Code will automatically compile everything with Maven and start Tomcat.

### Method 2: The Maven Terminal Command

If you prefer using the terminal, you must use the Maven wrapper script included in your project root, not `javac`.

1. Open your terminal in VS Code.
2. Make sure you are in the very root of your project: `PS F:\modular>`
3. Run this exact command:
```powershell
.\mvnw spring-boot:run

```