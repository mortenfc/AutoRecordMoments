/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Cloud Functions - createPaymentIntent + getCurrencyRules
 *
 * Changes:
 * - FINAL FIX: Checks for `requestHash` in addition to `nonce` to support the Standard API.
 * - CORRECTED: Properly parses the nested `tokenPayloadExternal` from the API response.
 * - Robust base64/base64url normalization and byte-wise hash comparison
 * - Better logging of decoded payloads and hex hashes for debugging
 * - Defensive handling of missing nonce / malformed responses
 */

import { onRequest } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { defineSecret } from "firebase-functions/params";
import Stripe from "stripe";
import { GoogleAuth } from "google-auth-library";
import * as crypto from "crypto";

// Allowed package names from client
const ALLOWED_PACKAGE_NAMES = [
  "com.mfc.recentaudiobuffer",
  "com.mfc.recentaudiobuffer.debug",
];

// --- Secret Definition ---
const stripeLiveSecret = defineSecret("STRIPE_LIVE_SECRET");
const stripeTestSecret = defineSecret("STRIPE_SECRET");

// --- Currency Configuration ---
const currencyRules: Record<string, { multiplier: number; min: number }> = {
  SEK: { multiplier: 100, min: 500 }, // 5.00 SEK
  DKK: { multiplier: 100, min: 500 }, // 5.00 DKK
  NOK: { multiplier: 100, min: 500 }, // 5.00 NOK
  USD: { multiplier: 100, min: 50 }, // $0.50 USD
  EUR: { multiplier: 100, min: 50 }, // €0.50 EUR
  GBP: { multiplier: 100, min: 30 }, // £0.30 GBP
  JPY: { multiplier: 1, min: 50 }, // ¥50 JPY
} as const;

// --- Helper type definition for the Play Integrity response payload (minimal) ---
interface PlayIntegrityPayload {
  requestDetails?: {
    requestPackageName?: string;
    nonce?: string;
    // Add requestHash to the type definition for clarity
    requestHash?: string;
    timestampMillis?: string;
  };
  appIntegrity?: {
    appRecognitionVerdict?: "PLAY_RECOGNIZED" | "UNRECOGNIZED_VERSION" | "UNEVALUATED";
    packageName?: string;
    certificateSha256Digest?: string[];
    versionCode?: string;
  };
  deviceIntegrity?: {
    deviceRecognitionVerdict?: (
      | "MEETS_DEVICE_INTEGRITY"
      | "MEETS_BASIC_INTEGRITY"
      | "MEETS_VIRTUAL_INTEGRITY"
    )[];
  };
  accountDetails?: {
    appLicensingVerdict?: "LICENSED" | "UNLICENSED" | "UNEVALUATED";
  };
}

// --- Simple currency rules endpoint ---
export const getCurrencyRules = onRequest({ cors: true }, (req, res) => {
  logger.info("Serving currency rules");
  res.json(currencyRules);
});

// --- Hash creation (must match client exactly) ---
function createRequestHash(amount: number, currency: string): string {
  const dataToHash = `${amount}:${currency.toUpperCase()}`;
  logger.info("Creating hash for data:", { dataToHash, amount, currency });
  return crypto.createHash("sha256").update(dataToHash, "utf8").digest("base64url");
}

// --- Helper: Accept base64 or base64url; produce Buffer or null if invalid ---
function base64UrlOrBase64ToBuffer(s?: string): Buffer | null {
  if (!s) return null;
  // Normalize URL-safe -> standard base64
  let b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  // Add padding if missing
  const pad = 4 - (b64.length % 4);
  if (pad !== 4) {
    b64 += "=".repeat(pad);
  }
  try {
    return Buffer.from(b64, "base64");
  } catch (e) {
    logger.warn("Failed base64 decode in base64UrlOrBase64ToBuffer", { raw: s, err: String(e) });
    return null;
  }
}
// --- Main Cloud Function to create a Payment Intent ---
export const createPaymentIntent = onRequest(
  { secrets: [stripeLiveSecret, stripeTestSecret], cors: true },
  async (req, res) => {
    try {
      const { amount, environment, currency, integrityToken, packageName } = req.body ?? {};

      // Basic request validation
      if (!packageName || !ALLOWED_PACKAGE_NAMES.includes(packageName)) {
        logger.error("Invalid or missing package name received:", packageName);
        res.status(400).json({ error: "Bad Request: Invalid package name." });
        return;
      }

      if (!integrityToken) {
        logger.warn("Request received without an integrity token.");
        res.status(400).json({ error: "Bad Request: Missing integrity token." });
        return;
      }

      // Validate currency + amount early (before hashing)
      if (typeof currency !== "string" || !(currency in currencyRules)) {
        logger.error("Unsupported currency received:", currency);
        res.status(400).json({ error: "Currency not supported." });
        return;
      }
      const rule = currencyRules[currency];

      if (!Number.isInteger(amount) || amount < rule.min) {
        const minAmountInMajorUnit = rule.min / rule.multiplier;
        logger.error(`Invalid amount for ${currency}: ${amount}`);
        res.status(400).json({
          error: `Amount must be at least ${minAmountInMajorUnit} ${currency}.`,
        });
        return;
      }

      // Now safe to create the expected hash (must match client)
      const expectedHash = createRequestHash(amount, currency);

      // 2. Verify the Play Integrity Token via a direct REST API call
      try {
        const auth = new GoogleAuth({
          scopes: "https://www.googleapis.com/auth/playintegrity",
        });

        const client = await auth.getClient();
        const accessTokenResp = await client.getAccessToken();
        const authToken =
          typeof accessTokenResp === "string" ? accessTokenResp : accessTokenResp?.token;

        if (!authToken) {
          logger.error("Failed to obtain auth token for Play Integrity API");
          res.status(500).json({ error: "Failed to verify client integrity." });
          return;
        }

        const endpoint = `https://playintegrity.googleapis.com/v1/${packageName}:decodeIntegrityToken`;

        const apiResponse = await fetch(endpoint, {
          method: "POST",
          headers: {
            Authorization: `Bearer ${authToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            integrity_token: integrityToken,
          }),
        });

        if (!apiResponse.ok) {
          let errorBody: unknown = null;
          try {
            errorBody = await apiResponse.json();
          } catch {
            errorBody = await apiResponse.text();
          }
          logger.error("Play Integrity API responded with an error", {
            status: apiResponse.status,
            errorBody,
          });
          throw new Error(`Play Integrity API call failed with status ${apiResponse.status}`);
        }

        const responseJson = await apiResponse.json();
        const tokenPayload: PlayIntegrityPayload | undefined = responseJson.tokenPayloadExternal;

        if (!tokenPayload) {
            logger.error("Play Integrity response was missing the 'tokenPayloadExternal' object.", { responseJson });
            res.status(500).json({ error: "Invalid response from integrity server." });
            return;
        }

        logger.info("Decoded Play Integrity token fields:", {
          hasRequestDetails: !!tokenPayload.requestDetails,
          hasNonce: !!tokenPayload.requestDetails?.nonce,
          hasRequestHash: !!tokenPayload.requestDetails?.requestHash, // For debugging
          appRecognitionVerdict: tokenPayload.appIntegrity?.appRecognitionVerdict ?? null,
          deviceVerdicts: tokenPayload.deviceIntegrity?.deviceRecognitionVerdict ?? null,
        });

        // --- THE FIX IS HERE ---
        // The Standard API uses `requestHash`, while the Classic API uses `nonce`. Check for both.
        const googleHash = tokenPayload.requestDetails?.requestHash || tokenPayload.requestDetails?.nonce;

        // Defensive: if there is no hash/nonce, fail early
        if (!googleHash) {
          logger.error("Play Integrity token missing requestDetails.nonce or requestDetails.requestHash", {
            tokenPayloadRequestDetails: tokenPayload.requestDetails ?? null,
          });
          res.status(403).json({ error: "Forbidden: Request integrity mismatch." });
          return;
        }

        const tokenPackage = tokenPayload.requestDetails?.requestPackageName;
        if (tokenPackage && tokenPackage !== packageName) {
          logger.error("Package name mismatch in integrity token", { tokenPackage, packageName });
          res.status(403).json({ error: "Forbidden: Request integrity mismatch." });
          return;
        }

        const expectedBuf = base64UrlOrBase64ToBuffer(expectedHash);
        const googleBuf = base64UrlOrBase64ToBuffer(googleHash);

        if (!expectedBuf || !googleBuf) {
          logger.error("Failed to parse base64 for hash comparison", {
            expectedHash,
            googleHash,
          });
          res.status(403).json({ error: "Forbidden: Request integrity mismatch." });
          return;
        }

        logger.info("Hash compare (hex)", {
          expectedHex: expectedBuf.toString("hex"),
          expectedLen: expectedBuf.length,
          googleHex: googleBuf.toString("hex"),
          googleLen: googleBuf.length,
        });

        if (!expectedBuf.equals(googleBuf)) {
          logger.error("Request hash mismatch. Possible tampering.", {
            expectedHash,
            googleHash: googleHash,
          });
          res.status(403).json({ error: "Forbidden: Request integrity mismatch." });
          return;
        }

        logger.info("Request hash verification passed.");

        const appIntegrity = tokenPayload.appIntegrity;
        const deviceIntegrity = tokenPayload.deviceIntegrity;
        const verdicts = deviceIntegrity?.deviceRecognitionVerdict || [];
        let isDeviceOk = false;

        if (packageName.endsWith(".debug")) {
          logger.info("Performing integrity check for DEBUG build.");
          isDeviceOk =
            verdicts.includes("MEETS_BASIC_INTEGRITY") ||
            verdicts.includes("MEETS_DEVICE_INTEGRITY") ||
            verdicts.includes("MEETS_VIRTUAL_INTEGRITY");
        } else {
          logger.info("Performing integrity check for PRODUCTION build.");
          isDeviceOk =
            verdicts.includes("MEETS_BASIC_INTEGRITY") ||
            verdicts.includes("MEETS_DEVICE_INTEGRITY");
        }

        if (!isDeviceOk) {
          logger.error("Failed device integrity check for build type.", {
            packageName,
            verdict: verdicts.length > 0 ? verdicts : "FIELD_MISSING",
          });
          res.status(403).json({ error: "Forbidden: Device integrity check failed." });
          return;
        }

        if (appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") {
          logger.error("Failed app integrity check.", {
            verdict: appIntegrity?.appRecognitionVerdict ?? "FIELD_MISSING",
          });
          res.status(403).json({ error: "Forbidden: App integrity check failed." });
          return;
        }

        logger.info("Integrity check passed.");
      } catch (error) {
        if (error instanceof Error) {
          logger.error("Error verifying integrity token:", error.message, { fullError: error });
        } else {
          logger.error("An unexpected error occurred during integrity verification:", error);
        }
        res.status(500).json({ error: "Failed to verify client integrity." });
        return;
      }

      // 3. Proceed with Stripe payment logic
      let stripeSecret: string | undefined;
      try {
        stripeSecret =
          environment === "production"
            ? stripeLiveSecret.value()
            : stripeTestSecret.value();
      } catch (err) {
        logger.error("Failed to load stripe secret from defineSecret:", String(err));
        res.status(500).json({ error: "Server misconfiguration: payment secret not available." });
        return;
      }

      if (!stripeSecret) {
        logger.error("Stripe secret was empty or undefined");
        res.status(500).json({ error: "Server misconfiguration: payment secret not available." });
        return;
      }

      logger.info(`Creating intent for ${amount} ${currency.toLowerCase()}`);
      logger.info("Using environment: " + (environment || "test"));

      const stripe = new Stripe(stripeSecret, {
        apiVersion: "2025-07-30.basil",
      });

      const paymentIntent = await stripe.paymentIntents.create({
        amount: amount,
        currency: currency.toLowerCase(),
        automatic_payment_methods: { enabled: true },
      });

      res.json({clientSecret: paymentIntent.client_secret});
    } catch (error) {
      if (error instanceof Error) {
        logger.error("Error creating PaymentIntent:", error.message, { fullError: error });
      } else {
        logger.error("An unexpected error occurred while creating PaymentIntent:", error);
      }
      res.status(500).json({ error: "Failed to create PaymentIntent" });
    }
  }
);
