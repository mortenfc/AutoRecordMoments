import {onRequest} from "firebase-functions/v2/https";
import {logger} from "firebase-functions/v2";
import {defineSecret} from "firebase-functions/params";
import Stripe from "stripe";

const stripeSecret = defineSecret("STRIPE_SECRET");

export const createPaymentIntent = onRequest({secrets: [stripeSecret]},
  async (req, res) => {
    const secretValue = await stripeSecret.value();
    logger.info("Secret value: " + secretValue);
    const stripe = new Stripe(secretValue, {
      apiVersion: "2024-12-18.acacia",
    });

    try {
      // Extract the amount from the request body
      const {amount} = req.body;

      // Validate the amount
      if (!amount || typeof amount !== "number" || amount < 500) {
        logger.error("Invalid amount received:", amount);
        res.status(400).json({error: "Invalid amount. Amount must be a number and at least 500 (5 SEK)."});
        return;
      }

      // Create the PaymentIntent with the dynamic amount
      const paymentIntent = await stripe.paymentIntents.create({
        amount: amount, // Use the amount from the request
        currency: "SEK",
        automatic_payment_methods: {enabled: true},
      });

      res.json({clientSecret: paymentIntent.client_secret});
    } catch (error) {
      console.error("Error creating PaymentIntent:", error);
      res.status(500).json({error: "Failed to create PaymentIntent"});
    }
  }
);
