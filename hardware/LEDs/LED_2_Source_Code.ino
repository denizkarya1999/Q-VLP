// define led according to pin diagram in article
// OOK sign number for LED 2 - 1100, repeated without a pause (one bit = 166 microseconds)
const int greenLed4 = 4;

void setup() {
  // initialize digital pin led as an output
  pinMode(greenLed4, OUTPUT);
}

void loop() {
    digitalWrite(greenLed4, HIGH);   // 1  turns on the Green LED
    delayMicroseconds(166);
    digitalWrite(greenLed4, HIGH);   // 1  keeps the Green LED on
    delayMicroseconds(166);
    digitalWrite(greenLed4, LOW);    // 0  turns off the Green LED
    delayMicroseconds(166);
    digitalWrite(greenLed4, LOW);    // 0  keeps the Green LED off
    delayMicroseconds(166);
}
