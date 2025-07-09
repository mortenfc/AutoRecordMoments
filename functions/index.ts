import {onRequest} from "firebase-functions/v2/https";
import {logger} from "firebase-functions/v2";
import {defineSecret} from "firebase-functions/params";
import Stripe from "stripe";

// 1. Define both secrets
const stripeLiveSecret = defineSecret("STRIPE_LIVE_SECRET");
const stripeTestSecret = defineSecret("STRIPE_SECRET");

// 2. Add both to the function's secrets array
export const createPaymentIntent = onRequest({secrets: [stripeLiveSecret, stripeTestSecret]},
  async (req, res) => {
    try {
      // 3. Get the amount AND the new environment flag from the request
      const {amount, environment} = req.body;

      // Validate amount...
      if (!amount || typeof amount !== "number" || amount < 500) {
        logger.error("Invalid amount received:", amount);
        res.status(400).json({error: "Invalid amount. Amount must be a number and at least 500 (5 SEK)."});
        return;
      }

      // 4. Choose the secret key based on the environment flag
      const stripeSecret = environment === "production" ?
        stripeLiveSecret.value() :
        stripeTestSecret.value();

      logger.info("Using environment: " + (environment || "test"));

      const stripe = new Stripe(stripeSecret, {
        apiVersion: "2024-12-18.acacia",
      });

      // Create the PaymentIntent...
      const paymentIntent = await stripe.paymentIntents.create({
        amount: amount,
        currency: "SEK",
        automatic_payment_methods: {enabled: true},
      });

      res.json({clientSecret: paymentIntent.client_secret});
    } catch (error) {
      logger.error("Error creating PaymentIntent:", error);
      res.status(500).json({error: "Failed to create PaymentIntent"});
    }
  }
);