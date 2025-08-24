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
 * Cloud Functions for handling Stripe payments with Play Integrity verification.
 * This function verifies the integrity of the client app and device before
 * creating a Stripe Payment Intent.
 */

import { onRequest } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { defineSecret } from "firebase-functions/params";
import Stripe from "stripe";
import { GoogleAuth } from "google-auth-library";
import * as crypto from "crypto";

// --- Configuration ---
const ALLOWED_PACKAGE_NAMES = [
  "com.mfc.recentaudiobuffer",
  "com.mfc.recentaudiobuffer.debug",
];

const currencyRules: Record<string, { multiplier: number; min: number }> = {
  SEK: { multiplier: 100, min: 500 }, // 5.00 SEK
  DKK: { multiplier: 100, min: 500 }, // 5.00 DKK
  NOK: { multiplier: 100, min: 500 }, // 5.00 NOK
  USD: { multiplier: 100, min: 50 }, // $0.50 USD
  EUR: { multiplier: 100, min: 50 }, // €0.50 EUR
  GBP: { multiplier: 100, min: 30 }, // £0.30 GBP
  JPY: { multiplier: 1, min: 50 }, // ¥50 JPY
} as const;

// --- Secret Definition ---
const stripeLiveSecret = defineSecret("STRIPE_LIVE_SECRET");
const stripeTestSecret = defineSecret("STRIPE_SECRET");

// --- Type Definitions ---
interface PlayIntegrityPayload {
  requestDetails?: {
    requestPackageName?: string;
    requestHash?: string;
    timestampMillis?: string;
  };
  appIntegrity?: {
    appRecognitionVerdict?: "PLAY_RECOGNIZED" | "UNRECOGNIZED_VERSION" | "UNEVALUATED";
  };
  deviceIntegrity?: {
    deviceRecognitionVerdict?: (
      | "MEETS_DEVICE_INTEGRITY"
      | "MEETS_BASIC_INTEGRITY"
      | "MEETS_VIRTUAL_INTEGRITY"
    )[];
  };
}

interface PaymentRequestBody {
    amount: number;
    environment: string;
    currency: string;
    integrityToken: string;
    packageName: string;
}

// --- Helper Functions ---

/**
 * Creates a SHA-256 hash of the amount and currency.
 * This must generate the exact same string as the client-side implementation.
 */
function createRequestHash(amount: number, currency: string): string {
  const dataToHash = `${amount}:${currency.toUpperCase()}`;
  return crypto.createHash("sha256").update(dataToHash, "utf8").digest("base64url");
}

/**
 * Verifies the Play Integrity token and its contents.
 * Throws an error if any check fails.
 */
async function verifyPlayIntegrity(body: PaymentRequestBody, expectedHash: string): Promise<void> {
  const { integrityToken, packageName } = body;

  const auth = new GoogleAuth({ scopes: "https://www.googleapis.com/auth/playintegrity" });
  const client = await auth.getClient();
  const accessTokenResp = await client.getAccessToken();
  const authToken = typeof accessTokenResp === "string" ? accessTokenResp : accessTokenResp?.token;

  if (!authToken) {
    throw new Error("Failed to obtain auth token for Play Integrity API");
  }

  const endpoint = `https://playintegrity.googleapis.com/v1/${packageName}:decodeIntegrityToken`;
  const apiResponse = await fetch(endpoint, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${authToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ integrity_token: integrityToken }),
  });

  if (!apiResponse.ok) {
    const errorBody = await apiResponse.text();
    logger.error("Play Integrity API responded with an error", { status: apiResponse.status, errorBody });
    throw new Error(`Play Integrity API call failed with status ${apiResponse.status}`);
  }

  const responseJson = await apiResponse.json();
  const tokenPayload: PlayIntegrityPayload | undefined = responseJson.tokenPayloadExternal;

  if (!tokenPayload) {
    logger.error("Play Integrity response was missing 'tokenPayloadExternal' object.", { responseJson });
    throw new Error("Invalid response from integrity server.");
  }

  // 1. Verify Request Hash
  const googleHash = tokenPayload.requestDetails?.requestHash;
  if (googleHash !== expectedHash) {
    logger.error("Request hash mismatch. Possible tampering.", { expectedHash, googleHash });
    throw new Error("Forbidden: Request integrity mismatch.");
  }
  logger.info("Request hash verification passed.");

  // 2. Verify Device Integrity
  const verdicts = tokenPayload.deviceIntegrity?.deviceRecognitionVerdict || [];
  let isDeviceOk = false;
  if (packageName.endsWith(".debug")) {
    isDeviceOk = verdicts.includes("MEETS_BASIC_INTEGRITY") || verdicts.includes("MEETS_DEVICE_INTEGRITY") || verdicts.includes("MEETS_VIRTUAL_INTEGRITY");
  } else {
    isDeviceOk = verdicts.includes("MEETS_BASIC_INTEGRITY") || verdicts.includes("MEETS_DEVICE_INTEGRITY");
  }

  if (!isDeviceOk) {
    logger.error("Failed device integrity check.", { packageName, verdict: verdicts.length > 0 ? verdicts : "FIELD_MISSING" });
    throw new Error("Forbidden: Device integrity check failed.");
  }
  logger.info("Device integrity verification passed.");

  // 3. Verify App Integrity
  if (tokenPayload.appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") {
    logger.error("Failed app integrity check.", { verdict: tokenPayload.appIntegrity?.appRecognitionVerdict ?? "FIELD_MISSING" });
    throw new Error("Forbidden: App integrity check failed.");
  }
  logger.info("App integrity verification passed.");
}


// --- Cloud Functions ---

export const getCurrencyRules = onRequest({ cors: true }, (req, res) => {
  logger.info("Serving currency rules");
  res.json(currencyRules);
});

export const createPaymentIntent = onRequest(
  { secrets: [stripeLiveSecret, stripeTestSecret], cors: true },
  async (req, res) => {
    try {
      const body: PaymentRequestBody = req.body;
      const { amount, environment, currency, packageName } = body;

      // 1. Validate incoming request
      if (!packageName || !ALLOWED_PACKAGE_NAMES.includes(packageName)) {
        res.status(400).json({ error: "Bad Request: Invalid package name." });
        return;
      }
      if (typeof currency !== "string" || !(currency in currencyRules)) {
        res.status(400).json({ error: "Currency not supported." });
        return;
      }
      const rule = currencyRules[currency];
      if (!Number.isInteger(amount) || amount < rule.min) {
        const minAmountInMajorUnit = rule.min / rule.multiplier;
        res.status(400).json({ error: `Amount must be at least ${minAmountInMajorUnit} ${currency}.` });
        return;
      }

      // 2. Verify client integrity
      try {
        const expectedHash = createRequestHash(amount, currency);
        await verifyPlayIntegrity(body, expectedHash);
        logger.info("All integrity checks passed.");
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : "An unknown integrity error occurred.";
        res.status(403).json({ error: errorMessage });
        return;
      }

      // 3. Proceed with Stripe payment logic
      const stripeSecret = environment === "production" ? stripeLiveSecret.value() : stripeTestSecret.value();
      if (!stripeSecret) {
        logger.error("Stripe secret was empty or undefined for environment:", environment);
        res.status(500).json({ error: "Server misconfiguration: payment secret not available." });
        return;
      }

      const stripe = new Stripe(stripeSecret, { apiVersion: "2025-07-30.basil" });
      const paymentIntent = await stripe.paymentIntents.create({
        amount,
        currency: currency.toLowerCase(),
        automatic_payment_methods: { enabled: true },
      });

      res.json({ clientSecret: paymentIntent.client_secret });

    } catch (error) {
      logger.error("Error in createPaymentIntent:", error);
      res.status(500).json({ error: "Failed to create PaymentIntent." });
    }
  }
);
