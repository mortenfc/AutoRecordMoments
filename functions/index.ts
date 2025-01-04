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
      const paymentIntent = await stripe.paymentIntents.create({
        amount: 500, // Minimum amount is 300 cents = 3 SEK
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
