const express = require("express");
const axios = require("axios");

const app = express();

app.use(express.json());

const PORT = process.env.PORT || 3000;

app.get("/", (req, res) => {
  res.send("INFINITE M-Pesa backend is online!");
});

async function getAccessToken() {

  const credentials =
    Buffer.from(
      process.env.CONSUMER_KEY +
      ":" +
      process.env.CONSUMER_SECRET
    ).toString("base64");

  const response = await axios.get(
    "https://sandbox.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials",
    {
      headers: {
        Authorization: "Basic " + credentials
      }
    }
  );

  return response.data.access_token;
}

app.post("/pay", async (req, res) => {

  try {

    const { phone } = req.body;

    if (!phone) {
      return res.status(400).json({
        success: false,
        message: "Phone number required"
      });
    }

    const token = await getAccessToken();

    const timestamp = new Date()
      .toISOString()
      .replace(/[-:TZ.]/g, "")
      .slice(0,14);

    const password = Buffer.from(
      process.env.MPESA_SHORTCODE +
      process.env.MPESA_PASSKEY +
      timestamp
    ).toString("base64");

    const response = await axios.post(

      "https://sandbox.safaricom.co.ke/mpesa/stkpush/v1/processrequest",

      {
        BusinessShortCode:
          process.env.MPESA_SHORTCODE,

        Password: password,

        Timestamp: timestamp,

        TransactionType:
          "CustomerPayBillOnline",

        Amount: 100,

        PartyA: phone,

        PartyB:
          process.env.MPESA_SHORTCODE,

        PhoneNumber: phone,

        CallBackURL:
          process.env.CALLBACK_URL,

        AccountReference:
          "INFINITE",

        TransactionDesc:
          "INFINITE Seller Access"
      },

      {
        headers: {
          Authorization:
            "Bearer " + token,

          "Content-Type":
            "application/json"
        }
      }
    );

    res.json({
      success: true,
      data: response.data
    });

  } catch(error) {

    console.error(
      error.response?.data ||
      error.message
    );

    res.status(500).json({
      success:false,
      message:"Payment request failed"
    });

  }

});


app.post("/mpesa/callback", (req,res) => {

  console.log(
    "M-Pesa callback:",
    JSON.stringify(
      req.body,
      null,
      2
    )
  );

  res.json({
    ResultCode:0,
    ResultDesc:"Accepted"
  });

});


app.listen(PORT, () => {

  console.log(
    "INFINITE backend running on port " +
    PORT
  );

});
