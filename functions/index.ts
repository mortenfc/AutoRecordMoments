import {onRequest} from "firebase-functions/v2/https";
import {logger} from "firebase-functions/v2";
import {defineSecret} from "firebase-functions/params";
import Stripe from "stripe";

// --- Secret Definition ---
const stripeLiveSecret = defineSecret("STRIPE_LIVE_SECRET");
const stripeTestSecret = defineSecret("STRIPE_SECRET");

// --- Currency Configuration ---
// This is your source of truth. For an even better approach, store this in Firebase Remote Config.
const currencyRules : Record<string, {multiplier: number, min: number}> = {
  "SEK": { multiplier: 100, min: 500 },      // 5.00 SEK
  "DKK": { multiplier: 100, min: 500 },      // 5.00 DKK
  "NOK": { multiplier: 100, min: 500 },      // 5.00 NOK
  "USD": { multiplier: 100, min: 50 },       // $0.50 USD
  "EUR": { multiplier: 100, min: 50 },       // €0.50 EUR
  "GBP": { multiplier: 100, min: 30 },       // £0.30 GBP
  "JPY": { multiplier: 1,   min: 50 },       // ¥50 JPY
} as const;

// 1. Cloud Function to provide currency rules to the app
export const getCurrencyRules = onRequest({cors: true}, (req, res) => {
  logger.info("Serving currency rules");
  res.json(currencyRules);
});

export const createPaymentIntent = onRequest({secrets: [stripeLiveSecret, stripeTestSecret], cors: true},
  async (req, res) => {
    try {
      // Amount from client is in major units (e.g., 5.00 for 5 dollars)
      const {amount, environment, currency} = req.body;

      if (typeof currency !== "string" || !(currency in currencyRules)) {
        logger.error("Unsupported currency received:", currency);
        res.status(400).json({error: "Currency not supported."});
        return;
      }

      const rule = currencyRules[currency];
      const minAmountInMajorUnit = rule.min / rule.multiplier;

      if (!amount || typeof amount !== "number" || amount < minAmountInMajorUnit) {
        logger.error(`Invalid amount for ${currency}: ${amount}`);
        res.status(400).json({error: `Amount must be at least ${minAmountInMajorUnit} ${currency}.`});
        return;
      }

      const amountInSmallestUnit = Math.round(amount * rule.multiplier);

      const stripeSecret = environment === "production" ?
        stripeLiveSecret.value() :
        stripeTestSecret.value();

      logger.info(`Creating intent for ${amountInSmallestUnit} ${currency.toLowerCase()}`);
      logger.info("Using environment: " + (environment || "test"));

      const stripe = new Stripe(stripeSecret, {
        apiVersion: "2025-07-30.basil",
      });

      const paymentIntent = await stripe.paymentIntents.create({
        amount: amountInSmallestUnit,
        currency: currency.toLowerCase(),
        automatic_payment_methods: {enabled: true},
      });

      res.json({clientSecret: paymentIntent.client_secret});
    } catch (error) {
      logger.error("Error creating PaymentIntent:", error);
      res.status(500).json({error: "Failed to create PaymentIntent"});
    }
  }
);