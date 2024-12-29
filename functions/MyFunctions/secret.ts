import {defineSecret} from "firebase-functions/params";

export const stripeSecret = defineSecret("STRIPE_SECRET");
