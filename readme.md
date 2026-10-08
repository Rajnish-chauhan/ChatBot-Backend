# Enterprise RAG Chatbot with Multimodal AI

![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.x-blue.svg)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-PgVector-blue.svg)
![React](https://img.shields.io/badge/React-19%2B-blue.svg)

An enterprise-grade, full-stack AI Chatbot integrating **Retrieval-Augmented Generation (RAG)**, **Multimodal Vision**, and **Real-Time Web Search**. Built with bleeding-edge technologies including **Spring Boot 4.x**, **Spring AI 2.x**, and **React 19+**, this application demonstrates production-ready features such as secure authentication (JWT & OAuth2), rate limiting, complex document parsing (PDF/TXT), and vector search capabilities.

## Key Features

* **Advanced AI Capabilities (Spring AI 2.x):**
    * **RAG Integration:** Queries an internal PostgreSQL Vector Database to provide context-aware answers based on company policies and data.
    * **Multimodal File Uploads:** Supports drag-and-drop uploads for Images (Vision AI) and Documents (PDFs, TXT, DOCX via Apache Tika and Spring AI native readers).
    * **Real-time Web Search:** Integrates the Tavily Search API as a fallback tool for real-time, up-to-date knowledge retrieval.
* **Robust Security & Authentication:**
    * **Multi-Tier Login:** Supports Google OAuth2, Email/OTP Registration, and traditional Username/Password login.
    * **Guest Mode:** Frictionless onboarding allowing users to test the app as a guest, with a seamless upgrade path to a permanent account via OTP.
    * **Stateless JWT Security:** Secured backend endpoints using custom JWT filters and Spring Security.
* **Enterprise Rate Limiting:** Implemented **Bucket4j** to restrict API usage (10 queries/day for guests, 100 queries/day for registered users) to prevent abuse and manage LLM API costs.
* **Data Privacy & Management:** Complete user control over data, featuring cascading deletion of sessions, chat histories, and accounts.

---

## Application Gallery

### Authentication & Access
| Standard Login | OTP Registration | Guest Mode Access |
|:---:|:---:|:---:|
| <img src="docs/login.png" width="300" alt="Login Screen"/> | <img src="docs/registerviaotp.png" width="300" alt="OTP Registration"/> | <img src="docs/guest.png" width="300" alt="Guest Login"/> |

### AI Interaction & Data Management
|                   Multimodal Chat Interface                   | Account Deletion (Danger Zone) |                 Vector Database (Pgvector)                 |
|:-------------------------------------------------------------:|:---:|:----------------------------------------------------------:|
| <img src="docs/chatai.png" width="300" alt="Chat Interface"/> | <img src="docs/delete.png" width="300" alt="Delete Modal"/> | <img src="docs/db.png" width="300" alt="Database Schema"/> |

---

## Tech Stack & Architecture

### Backend (Java / Spring Boot)
* **Core:** Java 21, Spring Boot 4.x, Spring WebFlux (for Server-Sent Events/Streaming)
* **AI & RAG:** Spring AI 2.x, OpenAI GPT-4o-mini, Apache Tika (Document Parsing)
* **Security:** Spring Security, JWT (io.jsonwebtoken), OAuth2 Client
* **Database:** PostgreSQL with **Pgvector** extension, Spring Data JPA
* **API Protection:** Bucket4j (Token-bucket rate limiting)
* **Mail:** Java Mail Sender (OTP delivery)

### Frontend (React)
* **Core:** React.js 19+, Vite
* **Styling:** Tailwind CSS (Dark/Light mode support)
* **API Communication:** Fetch API with custom interceptors for 401/429 error handling

---

## Getting Started

### Prerequisites
* **Java 21+**
* Node.js & npm (Latest LTS recommended)
* Docker (for PostgreSQL + Pgvector)
* API Keys for OpenAI and Tavily Search

### 1. Database Setup (Docker)
Start the PostgreSQL container with the Pgvector extension enabled:
```bash
docker run -d --name vector-db \
  -e POSTGRES_USER=root \
  -e POSTGRES_PASSWORD=root \
  -e POSTGRES_DB=vector-db \
  -p 5432:5432 \
  ankane/pgvector:latest
```
### 2. Backend Environment Variables
* Create an application.properties or set your environment variables:
```text
# LLM & Search Config
OPENAI_API_KEY=your_openai_api_key_here
tavily.api-key=your_tavily_api_key_here

# OAuth Config
GOOGLE_CLIENT_ID=your_google_client_id
GOOGLE_CLIENT_SECRET=your_google_client_secret

# Email Config (For OTP)
GMAIL_USERNAME=your_email@gmail.com
GMAIL_APP_PASSWORD=your_app_password
```
### 3. Run the Backend
Navigate to the root directory and start the Spring Boot application:
```text
mvn spring-boot:run
```
### 4. Run the Frontend
Navigate to the frontend directory:
```text
npm install
npm run dev
```

## Architectural Flow
1. User Input: User submits a prompt (with or without a file).

2. Rate Check: RateLimitFilter (Bucket4j) verifies the user has sufficient tokens.

3. Retrieval (RAG): The backend queries Pgvector for semantic matches to the user's prompt based on internal documents.

4. Multimodal Processing: If a PDF/TXT is attached, Tika extracts the text. If an image is attached, it is formatted as a Media object for Vision AI.

5. LLM Generation: Spring AI constructs a comprehensive system prompt combining the RAG context, extracted file text, and user question, then streams the response back via SSE.

## 👨‍💻 Author
**Rajnish Chauhan**

Java Backend & AI Integration Engineer

🌐 [Portfolio: rajnishsystems.in](https://rajnishsystems.in)