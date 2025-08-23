import {onRequest} from "firebase-functions/v2/https";
import {logger} from "firebase-functions/v2";
import {defineSecret} from "firebase-functions/params";
import Stripe from "stripe";

// We only need the auth library to get a token.
import {GoogleAuth} from "google-auth-library";

const ALLOWED_PACKAGE_NAMES = [
  "com.mfc.recentaudiobuffer",
  "com.mfc.recentaudiobuffer.debug",
];

// --- Secret Definition ---
const stripeLiveSecret = defineSecret("STRIPE_LIVE_SECRET");
const stripeTestSecret = defineSecret("STRIPE_SECRET");

// --- Currency Configuration ---
const currencyRules : Record<string, {multiplier: number, min: number}> = {
  "SEK": { multiplier: 100, min: 500 },      // 5.00 SEK
  "DKK": { multiplier: 100, min: 500 },      // 5.00 DKK
  "NOK": { multiplier: 100, min: 500 },      // 5.00 NOK
  "USD": { multiplier: 100, min: 50 },       // $0.50 USD
  "EUR": { multiplier: 100, min: 50 },       // €0.50 EUR
  "GBP": { multiplier: 100, min: 30 },       // £0.30 GBP
  "JPY": { multiplier: 1,   min: 50 },       // ¥50 JPY
} as const;

// --- Helper type definition for the response payload ---
// This is based on Google's official documentation for the JSON response.
interface PlayIntegrityPayload {
  requestDetails: {
    requestPackageName: string;
    nonce: string;
    timestampMillis: string;
  };
  appIntegrity: {
    appRecognitionVerdict: "PLAY_RECOGNIZED" | "UNRECOGNIZED_VERSION" | "UNEVALUATED";
    packageName: string;
    certificateSha256Digest: string[];
    versionCode: string;
  };
  deviceIntegrity: {
    deviceRecognitionVerdict: ("MEETS_DEVICE_INTEGRITY" | "MEETS_BASIC_INTEGRITY" | "MEETS_VIRTUAL_INTEGRITY")[];
  };
  accountDetails: {
    appLicensingVerdict: "LICENSED" | "UNLICENSED" | "UNEVALUATED";
  };
}

// --- Cloud Function to provide currency rules ---
export const getCurrencyRules = onRequest({cors: true}, (req, res) => {
  logger.info("Serving currency rules");
  res.json(currencyRules);
});

// --- Main Cloud Function to create a Payment Intent ---
export const createPaymentIntent = onRequest({secrets: [stripeLiveSecret, stripeTestSecret], cors: true},
  async (req, res) => {
    try {
      const {amount, environment, currency, integrityToken, packageName} = req.body;

      // 1. Validate incoming request
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

      // 2. Verify the Play Integrity Token via a direct REST API call
      try {
        const auth = new GoogleAuth({
          scopes: "https://www.googleapis.com/auth/playintegrity",
        });
        const authToken = await auth.getAccessToken();

        const endpoint = `https://playintegrity.googleapis.com/v1/${packageName}:decodeIntegrityToken`;

        const apiResponse = await fetch(endpoint, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${authToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            // Note: the field name in the direct REST API is snake_case
            integrity_token: integrityToken,
          }),
        });

        if (!apiResponse.ok) {
          const errorBody = await apiResponse.json();
          logger.error("Play Integrity API responded with an error", {
            status: apiResponse.status,
            errorBody: errorBody,
          });
          throw new Error(`API call failed with status ${apiResponse.status}`);
        }

        const tokenPayload: PlayIntegrityPayload = await apiResponse.json();

        const appIntegrity = tokenPayload.appIntegrity;
        const deviceIntegrity = tokenPayload.deviceIntegrity;

        // Check the verdicts safely
        if (!deviceIntegrity?.deviceRecognitionVerdict?.includes("MEETS_BASIC_INTEGRITY")) {
          logger.error("Failed device integrity check.", {verdict: deviceIntegrity?.deviceRecognitionVerdict || "FIELD_MISSING"});
          res.status(403).json({error: "Forbidden: Device integrity check failed."});
          return;
        }
        if (appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") {
          logger.error("Failed app integrity check.", {verdict: appIntegrity?.appRecognitionVerdict || "FIELD_MISSING"});
          res.status(403).json({error: "Forbidden: App integrity check failed."});
          return;
        }

        logger.info("Integrity check passed.");

      } catch (error) {
        if (error instanceof Error) {
          logger.error("Error verifying integrity token:", error.message, {fullError: error});
        } else {
          logger.error("An unexpected error occurred during integrity verification:", error);
        }
        res.status(500).json({error: "Failed to verify client integrity."});
        return;
      }

      // 3. Proceed with Stripe payment logic
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