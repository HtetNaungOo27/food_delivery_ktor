# Food Delivery Ktor Backend

Kotlin/Ktor backend for the FoodHub food-delivery system. It provides authentication, restaurant and menu management, carts, orders, payments, notifications, image storage, rider workflows, and WebSocket location tracking.

## Requirements

- JDK 17
- MySQL 8.x
- Git
- Optional: IntelliJ IDEA or Android Studio with Kotlin support

The repository includes the Gradle wrapper, so team members do not need to install Gradle separately.

## Set up on a team member's computer

1. Clone and enter the repository:

   ```bash
   git clone <FOOD_DELIVERY_KTOR_REPOSITORY_URL>
   cd food_delivery_ktor
   ```

2. Create a local MySQL database:

   ```sql
   CREATE DATABASE food_delivery CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'foodhub'@'localhost' IDENTIFIED BY 'choose-a-local-password';
   GRANT ALL PRIVILEGES ON food_delivery.* TO 'foodhub'@'localhost';
   FLUSH PRIVILEGES;
   ```

3. Configure the database connection for your machine. The current code reads the JDBC URL, username, and password in `src/main/kotlin/com/codewithfk/database/DatabaseFactory.kt`. Use your own local values and never commit passwords. A follow-up improvement should move all three values to environment variables.
4. Configure optional integrations only if the feature is needed:

   - `STRIPE_API_KEY`: Stripe secret key used by payment code
   - `src/main/kotlin/com/codewithfk/configs/SupabaseConfig.kt`: Supabase URL and key for image storage
   - `src/main/kotlin/com/codewithfk/configs/GoogleConfigs.kt`: Google Maps key
   - Firebase Admin service-account JSON: keep it outside Git; the expected service-account filename is ignored by `.gitignore`

5. Start the server:

   macOS or Linux:

   ```bash
   ./gradlew run
   ```

   Windows:

   ```powershell
   .\gradlew.bat run
   ```

6. Verify that the API is listening at `http://localhost:8080`.

The application creates missing database tables during startup. Review seed and migration behavior before connecting it to a shared or production database.

## Android client connection

The FoodHub Android emulator reaches this server at `http://10.0.2.2:8080/`. A physical device must use the development computer's LAN IP address and both devices must be on the same network.

## Useful commands

```bash
./gradlew test
./gradlew build
./gradlew run
```

## Security checklist

- Never commit database passwords, Stripe keys, Supabase keys, Google Maps keys, JWT secrets, or Firebase service-account JSON.
- Use separate credentials for each developer and environment.
- Replace the placeholder JWT secret before any shared deployment.
- Keep `local.properties`, `.idea`, `.gradle`, and `build` out of Git.
- Rotate any credential immediately if it has ever been committed or shared publicly.

## Team workflow

- Keep the default branch deployable.
- Work in short-lived feature branches.
- Open a pull request and request review before merging.
