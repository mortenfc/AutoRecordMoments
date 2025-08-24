import {onRequest} from "firebase-functions/v2/https";
import {logger} from "firebase-functions/v2";
import {defineSecret} from "firebase-functions/params";
import Stripe from "stripe";
import {google} from "googleapis"; // <-- Use the official Google API client library
import * as crypto from "crypto";

// A list of allowed package names for your app (debug and release).
const ALLOWED_PACKAGE_NAMES = [
  "com.mfc.recentaudiobuffer",
  "com.mfc.recentaudiobuffer.debug",
];

// --- Secret Definition ---
// Define secrets for Stripe API keys using Firebase's parameter system.
const stripeLiveSecret = defineSecret("STRIPE_LIVE_SECRET");
const stripeTestSecret = defineSecret("STRIPE_SECRET");

// --- Currency Configuration ---
// Define rules for minimum amounts and multipliers for supported currencies.
const currencyRules: Record<string, {multiplier: number, min: number}> = {
  "SEK": { multiplier: 100, min: 500 },      // 5.00 SEK
  "DKK": { multiplier: 100, min: 500 },      // 5.00 DKK
  "NOK": { multiplier: 100, min: 500 },      // 5.00 NOK
  "USD": { multiplier: 100, min: 50 },       // $0.50 USD
  "EUR": { multiplier: 100, min: 50 },       // €0.50 EUR
  "GBP": { multiplier: 100, min: 30 },       // £0.30 GBP
  "JPY": { multiplier: 1,   min: 50 },       // ¥50 JPY
} as const;


// --- Cloud Function to provide currency rules to the client ---
export const getCurrencyRules = onRequest({cors: true}, (req, res) => {
  logger.info("Serving currency rules");
  res.json(currencyRules);
});

/**
 * Creates a SHA-256 hash of the amount and currency.
 * This must generate the exact same string as the client-side implementation.
 * @param {string | number} amount The amount of the transaction.
 * @param {string} currency The currency code (e.g., "USD").
 * @return {string} A base64url encoded hash string.
 */
function createRequestHash(amount: string | number, currency: string): string {
  // Explicitly convert to string and trim to prevent type or whitespace issues.
  const amountStr = String(amount);
  const currencyStr = String(currency).trim();
  const dataToHash = `${amountStr}:${currencyStr}`;

  logger.info("Server is hashing this string:", dataToHash);

  // Use base64url encoding to match the client's NO_PADDING, URL_SAFE settings.
  return crypto.createHash("sha256").update(dataToHash).digest("base64url");
}

// --- Main Cloud Function to create a Stripe Payment Intent ---
export const createPaymentIntent = onRequest({secrets: [stripeLiveSecret, stripeTestSecret], cors: true},
  async (req, res) => {
    try {
      const {amount, environment, currency, integrityToken, packageName} = req.body;

      // 1. Validate incoming request parameters
      if (!packageName || !ALLOWED_PACKAGE_NAMES.includes(packageName)) {
        logger.error("Invalid or missing package name received:", packageName);
        res.status(400).json({error: "Bad Request: Invalid package name."});
        return;
      }
      if (!integrityToken) {
        logger.warn("Request received without an integrity token.");
        res.status(400).json({error: "Bad Request: Missing integrity token."});
        return;
      }

      // Generate the hash on the server to compare with the one from the client.
      const expectedHash = createRequestHash(amount, currency);

      // 2. Verify the Play Integrity Token using the Google API Client Library
      try {
        // The library automatically handles authentication in the Cloud Functions environment.
        const playintegrity = google.playintegrity("v1");

        const apiResponse = await playintegrity.v1.decodeIntegrityToken({
          packageName: packageName,
          requestBody: {
            integrityToken: integrityToken,
          },
        });

        // The library provides a correctly typed response, including the nested payload.
        const tokenPayload = apiResponse.data.tokenPayloadExternal;

        if (!tokenPayload) {
          throw new Error("Invalid response from Play Integrity API: Missing token payload.");
        }

        const googleHash = tokenPayload.requestDetails?.nonce;

        // Compare the server-generated hash with the client-generated hash from the token.
        if (googleHash !== expectedHash) {
          logger.error("Request hash mismatch. Possible tampering.", {
            expectedHash: expectedHash,
            receivedHash: googleHash,
          });
          res.status(403).json({error: "Forbidden: Request integrity mismatch."});
          return;
        }

        logger.info("Request hash verification passed.");

        const appIntegrity = tokenPayload.appIntegrity;
        const deviceIntegrity = tokenPayload.deviceIntegrity;
        const verdicts = deviceIntegrity?.deviceRecognitionVerdict || [];
        let isDeviceOk = false;

        // Differentiate integrity requirements for debug vs. production builds.
        if (packageName.endsWith(".debug")) {
          logger.info("Performing integrity check for DEBUG build.");
          isDeviceOk =
                verdicts.includes("MEETS_BASIC_INTEGRITY") ||
                verdicts.includes("MEETS_DEVICE_INTEGRITY") ||
                verdicts.includes("MEETS_VIRTUAL_INTEGRITY");
        } else {
          logger.info("Performing integrity check for PRODUCTION build.");
          isDeviceOk = verdicts.includes("MEETS_DEVICE_INTEGRITY");
        }

        if (!isDeviceOk) {
          logger.error("Failed device integrity check for build type.", {
            packageName: packageName,
            verdict: verdicts.length > 0 ? verdicts : "FIELD_MISSING",
          });
          res.status(403).json({error: "Forbidden: Device integrity check failed."});
          return;
        }

        if (appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") {
          logger.error("Failed app integrity check.", {verdict: appIntegrity?.appRecognitionVerdict || "FIELD_MISSING"});
          res.status(403).json({error: "Forbidden: App integrity check failed."});
          return;
        }

        logger.info("All integrity checks passed.");

      } catch (error) {
        if (error instanceof Error) {
          logger.error("Error verifying integrity token:", error.message, {fullError: error});
        } else {
          logger.error("An unexpected error occurred during integrity verification:", error);
        }
        res.status(500).json({error: "Failed to verify client integrity."});
        return;
      }

      // 3. Proceed with Stripe payment logic if all checks passed
      if (typeof currency !== "string" || !(currency in currencyRules)) {
        logger.error("Unsupported currency received:", currency);
        res.status(400).json({error: "Currency not supported."});
        return;
      }

      const rule = currencyRules[currency];

      if (!Number.isInteger(amount) || amount < rule.min) {
        const minAmountInMajorUnit = rule.min / rule.multiplier;
        logger.error(`Invalid amount for ${currency}: ${amount}`);
        res.status(400).json({error: `Amount must be at least ${minAmountInMajorUnit} ${currency}.`});
        return;
      }

      const stripeSecret = environment === "production" ?
        stripeLiveSecret.value() :
        stripeTestSecret.value();

      logger.info(`Creating intent for ${amount} ${currency.toLowerCase()}`);
      logger.info("Using environment: " + (environment || "test"));

      const stripe = new Stripe(stripeSecret, {
        apiVersion: "2025-07-30.basil",
      });

      const paymentIntent = await stripe.paymentIntents.create({
        amount: amount,
        currency: currency.toLowerCase(),
        automatic_payment_methods: {enabled: true},
      });

      res.json({clientSecret: paymentIntent.client_secret});
    } catch (error) {
      if (error instanceof Error) {
        logger.error("Error creating PaymentIntent:", error.message, {fullError: error});
      } else {
        logger.error("An unexpected error occurred while creating PaymentIntent:", error);
      }
      res.status(500).json({error: "Failed to create PaymentIntent"});
    }
  }
);
