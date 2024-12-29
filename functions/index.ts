// import * as functions from "firebase-functions";
import {https} from "firebase-functions";
import {stripeSecret} from "./MyFunctions/secret";
import Stripe from "stripe";

export const createPaymentIntent = https.onRequest(
  async (req, res) => {
    const secretValue = await stripeSecret.value();
    const stripe = new Stripe(secretValue, {
      apiVersion: "2024-12-18.acacia",
    });

    try {
      const paymentIntent = await stripe.paymentIntents.create({
        amount: 5, // Example amount (in cents)
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
