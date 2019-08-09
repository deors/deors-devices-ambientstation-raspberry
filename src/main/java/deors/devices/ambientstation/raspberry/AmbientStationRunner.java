package deors.devices.ambientstation.raspberry;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import deors.devices.ambientstation.raspberry.publishers.Publisher;
import deors.devices.ambientstation.raspberry.publishers.PublisherFactory;

import org.iot.raspberry.grovepi.GroveDigitalIn;
import org.iot.raspberry.grovepi.GrovePi;
import org.iot.raspberry.grovepi.devices.GroveLed;
import org.iot.raspberry.grovepi.devices.GroveLightSensor;
import org.iot.raspberry.grovepi.devices.GroveSoundSensor;
import org.iot.raspberry.grovepi.devices.GroveTemperatureAndHumiditySensor;
import org.iot.raspberry.grovepi.devices.GroveTemperatureAndHumidityValue;
import org.iot.raspberry.grovepi.devices.GroveRgbLcd;
import org.iot.raspberry.grovepi.pi4j.GrovePi4J;

public class AmbientStationRunner {

    // ambient data bean
    private AmbientData ambientData;

    // configuration properties
    private Properties properties;

    // grovePi board
    private GrovePi grovePi;

    // button
    private GroveDigitalIn button;

    // green led
    private GroveLed greenLed;

    // blue led
    private GroveLed blueLed;

    // LCD
    private GroveRgbLcd lcd;

    // temperature & humidity sensor
    private GroveTemperatureAndHumiditySensor temperatureHumiditySensor;

    // air quality sensor
    private GroveAirQualitySensor airQualitySensor;

    // light sensor
    private GroveLightSensor lightSensor;

    // sound sensor
    private GroveSoundSensor soundSensor;

    // motion sensor
    private GroveDigitalIn motionSensor;

    // external publisher
    private Publisher publisher;

    // thread keep forever flag
    private volatile boolean keepRunning = true;

    // lcd publishing semaphore
    private volatile boolean lcdPublishing = false;

    // the logger
    private static Logger logger = Logger.getLogger(AmbientStationRunner.class.getName());

    public static void main(String[] args) {

        // initialise the log system
        String logConfigFile = System.getProperty("java.util.logging.config.file");
        try {
            InputStream logConfig;
            if (logConfigFile == null || logConfigFile.isEmpty()) {
                logConfig = AmbientStationRunner.class.getClassLoader().getResourceAsStream("logging.properties");
            } else {
                logConfig = new FileInputStream(logConfigFile);
            }
            LogManager.getLogManager().readConfiguration(logConfig);
        } catch (IOException ioe) {
            System.err.println("ERROR: log system could not be initialised: " + ioe.getMessage());
            System.err.println("HALT!");
            return;
        }

        try {
            new AmbientStationRunner().launch();
        } catch (IOException ioe) {
            logger.severe(String.format("ambient station could not be started: %s", ioe.getMessage()));
            return;
        }
    }

    private static String getConfigurationProperty(String envKey, String sysKey, String defValue) {

        String retValue = defValue;
        String envValue = System.getenv(envKey);
        String sysValue = System.getProperty(sysKey);
        // system property prevails over environment variable
        if (sysValue != null) {
            retValue = sysValue;
        } else if (envValue != null) {
            retValue = envValue;
        }
        return retValue;
    }

    private int getIntProperty(String key) {

        return Integer.parseInt(properties.getProperty(key));
    }

    private void launch() throws IOException {

        // properties file name is provided via environment variable or system property
        // a sensible default is assumed
        // note: executable jar created by 'Intel System Studio for IoT' has properties files
        // in a resources folder instead of at the root of the classpath as usual
        String propertiesFileName = getConfigurationProperty("AMBIENT_PROP_FILE", "ambient.properties.file", "/application.properties");

        logger.info(String.format("loading properties from file: %s", propertiesFileName));

        try {
            properties = new Properties();
            properties.load(this.getClass().getResourceAsStream(propertiesFileName));
        } catch (NullPointerException npe) {
            // not very elegant, but is the exception raised by Properties class
            // when the properties file does not exist or could not be found
            logger.severe(String.format("the properties file was not found or could not be read"));
            return;
        }

        // initialize the grovePi board
        grovePi = new GrovePi4J();

        // read the station id
        String stationId = properties.getProperty("device.id");

        logger.info(String.format("ambient station id: %s", stationId));

        // ambient data bean initialised with the station id
        ambientData = new AmbientData(stationId);

        // initialize monitor parts
        button = grovePi.getDigitalIn(getIntProperty("port.button"));
        greenLed = new GroveLed(grovePi, getIntProperty("port.greenLed"));
        blueLed = new GroveLed(grovePi, getIntProperty("port.blueLed"));
        lcd = grovePi.getLCD();
        clearLcd();

        // initialize sensor parts
        temperatureHumiditySensor = new GroveTemperatureAndHumiditySensor(
            grovePi, getIntProperty("port.temperatureHumidity"),
            GroveTemperatureAndHumiditySensor.Type.DHT22);
        airQualitySensor = new GroveAirQualitySensor(grovePi, getIntProperty("port.airQuality"));
        lightSensor = new GroveLightSensor(grovePi, getIntProperty("port.light"));
        soundSensor = new GroveSoundSensor(grovePi, getIntProperty("port.sound"));
        motionSensor = grovePi.getDigitalIn(getIntProperty("port.motion"));

        // loop forever reading information from sensors
        new Thread(() -> {
            while (keepRunning) {
                readAmbientData();
                logAmbientData();
                checkRanges();
                checkPublishLcd();
                pause(500);
            }
        }).start();

        // wait for some data to be collected
        pause(500);

        // data is published externally on a separate thread
        new Thread(() -> {
            while (keepRunning) {
                publishExternal();
                pause(5000);
            }
        }).start();
    }

    private void pause(long millisecs) {

        try {
            Thread.sleep(millisecs);
        } catch (InterruptedException e) {
        }
    }

    private void readAmbientData() {

        try {
            GroveTemperatureAndHumidityValue temperatureHumidityValue =
                readTemperatureHumidity();
            ambientData.setTemperatureValue(temperatureHumidityValue.getTemperature());
            ambientData.setHumidityValue(temperatureHumidityValue.getHumidity());
        } catch (IOException ex) {
            logger.severe(String.format("temperature and humidity could not be read: %s", ex.getMessage()));
        }

        try {
            ambientData.setAirQualityValue(readAirQuality()); // also sets air quality (qualitative)
        } catch (IOException ex) {
            logger.severe(String.format("air quality could not be read: %s", ex.getMessage()));
        }

        try {
            ambientData.setLightValue(readLight());
        } catch (IOException ex) {
            logger.severe(String.format("light could not be read: %s", ex.getMessage()));
        }

        try {
            ambientData.setSoundValue(readSound());
        } catch (IOException ex) {
            logger.severe(String.format("sound could not be read: %s", ex.getMessage()));
        }

        try {
            ambientData.setMotionDetected(readMotionDetected());
        } catch (IOException | InterruptedException ex) {
            logger.severe(String.format("motion detection could not be read: %s", ex.getMessage()));
        }

        blinkLed(greenLed);
    }

    private GroveTemperatureAndHumidityValue readTemperatureHumidity() throws IOException {

        // read temperature and humidity from sensor
        return temperatureHumiditySensor.get();
    }

    private double readAirQuality() throws IOException {

        // read air quality from sensor
        return airQualitySensor.get();
    }

    private double readLight() throws IOException {

        // read ambient light from sensor
        return lightSensor.get();
    }

    private double readSound() throws IOException {

        // read ambient sound from sensor
        return soundSensor.get();
    }

    private boolean readMotionDetected() throws IOException, InterruptedException {

        return motionSensor.get();
    }

    private void logAmbientData() {

        StringBuffer message = new StringBuffer();
        message.append("station ambient data at: %s%n");
        message.append("- temperature read from sensor: %3.1f%n");
        message.append("- humidity read from sensor: %3.1f%n");
        message.append("- ambient light read from sensor: %.0f%n");
        message.append("- ambient sound read from sensor: %.0f%n");
        message.append("- air quality read from sensor: %.0f / %s%n");
        message.append("- motion detected: %b");

        logger.info(String.format(message.toString(),
            LocalDateTime.now().toString(),
            ambientData.getTemperatureValue(),
            ambientData.getHumidityValue(),
            ambientData.getLightValue(),
            ambientData.getSoundValue(),
            ambientData.getAirQualityValue(),
            ambientData.getAirQuality(),
            ambientData.isMotionDetected()));
    }

    private void checkRanges() {

        ambientData.checkRanges();
    }

    private void blinkLed(GroveLed led) {

        new Thread(() -> {
            try {
                // blink the led for 200 ms to show that data was actually sampled
                led.set(true);
                pause(200);
                led.set(false);
            }
            catch (IOException ex) {
                logger.severe(String.format("led could not be set: %s", ex.getMessage()));
            }
        }).start();
    }

    private void blinkLedTwice(GroveLed led) {

        new Thread(() -> {
            try {
                // blink the led twice for 100 ms to show that data was actually sampled
                led.set(true);
                pause(100);
                led.set(false);
                pause(100);
                led.set(true);
                pause(100);
                led.set(false);
            }
            catch (IOException ex) {
                logger.severe(String.format("led could not be set: %s", ex.getMessage()));
            }
        }).start();
    }

    private void checkPublishLcd() {

        new Thread(() -> {
            try {
                if (button.get()) {
                    if (!lcdPublishing) {
                        lcdPublishing = true;
                        publishLcd();
                        if (button.get()) {
                            resetChanges();
                        }
                        clearLcd();
                        lcdPublishing = false;
                    }
                }
            }
            catch (IOException | InterruptedException ex) {
                logger.severe(String.format("button state could not be read: %s", ex.getMessage()));
            }
        }).start();
    }

    private void publishLcd() {

        // the temperature range in degrees celsius
        // used for reference for background colour
        final double minTempRange = 0;
        final double maxTempRange = 50;

        // LCD colour control
        double fade;
        int r, g, b;

        // set the fade value depending on where we are in the temperature range
        if (ambientData.getTemperatureValue() <= minTempRange) {
            fade = 0.0f;
        } else if (ambientData.getTemperatureValue() >= maxTempRange) {
            fade = 1.0f;
        } else {
            fade = (ambientData.getTemperatureValue() - minTempRange) / (maxTempRange - minTempRange);
        }

        // fade the colour components separately
        r = (int)(255 * fade);
        g = (int)(64 * fade);
        b = (int)(255 * (1 - fade));

        // apply the calculated background colour
        try {
            lcd.setRGB(r, g, b);
        }
        catch (IOException ex) {
            logger.severe(String.format("lcd colour could not be set: %s", ex.getMessage()));
        }

        // display the current date/time
        write16x2(
            "station data",
            LocalDateTime.now().toString());

        pause(1000);

        // display the temperature data on the LCD
        write16x2(
            String.format("temperature %.1f", ambientData.getTemperatureValue()),
            String.format("mn %.1f mx %.1f", ambientData.getMinTemperatureObserved(), ambientData.getMaxTemperatureObserved()));

        pause(1000);

        // display the humidity data on the LCD
        write16x2(
            String.format("humidity %.1f", ambientData.getHumidityValue()),
            String.format("mn %.1f mx %.1f", ambientData.getMinHumidityObserved(), ambientData.getMaxHumidityObserved()));

        pause(1000);

        // display the ambient light data on the LCD
        write16x2(
            String.format("light %.0f", ambientData.getLightValue()),
            String.format("mn %.0f mx %.0f", ambientData.getMinLightObserved(), ambientData.getMaxLightObserved()));

        pause(1000);

        // display the ambient sound data on the LCD
        write16x2(
            String.format("sound %.0f", ambientData.getSoundValue()),
            String.format("mn %.0f mx %.0f", ambientData.getMinSoundObserved(), ambientData.getMaxSoundObserved()));

        pause(1000);

        // display the air quality data on the LCD
        write16x2(
            String.format("air quality %.0f", ambientData.getAirQualityValue()),
            ambientData.getAirQuality().toString());

        pause(1000);

        // display the motion detection status
        write16x2(
            "motion detected",
            String.format("%b", ambientData.isMotionDetected()));

        pause(1000);
    }

    private void write16x2(String topLine, String bottomLine) {

        try {
            lcd.setText(padRight(topLine, 16, ' ') + padRight(bottomLine, 16, ' '));
        }
        catch (IOException ex) {
            logger.severe(String.format("lcd text could not be written: %s", ex.getMessage()));
        }
    }

    private static String padRight(String source, int length, char pad) {

        String temp = (source == null) ? "" : source;

        int difLength = length - temp.length();

        if (difLength > 0) {
            temp = temp + repeatCharacter(pad, difLength);
        } else {
            temp = temp.substring(0, length);
        }

        return temp;
    }

    private static String repeatCharacter(char repeat, int length) {

        if (length <= 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            sb.append(repeat);
        }

        return sb.toString();
    }

    private void resetChanges() {

        ambientData.resetRanges();

        try {
            lcd.setText("ranges reset");
        }
        catch (IOException ex) {
            logger.severe(String.format("lcd text could not be written: %s", ex.getMessage()));
        }

        pause(1000);
    }

    private void clearLcd() {

        try {
            lcd.setRGB(0, 0, 0);
            lcd.setText("");
        }
        catch (IOException ex) {
            logger.severe(String.format("lcd could not be cleared: %s", ex.getMessage()));
        }
    }

    private void publishExternal() {

        try {
            if (publisher == null) {
                openExternalPublisher();
            }
            if (publisher != null) {
                publisher.publish(ambientData.toJson());
                blinkLedTwice(blueLed);
            }
        } catch (IOException ioe) {
            logger.severe(String.format("information could not be published externally: %s", ioe.getMessage()));
            closeExternalPublisher();
        }
    }

    private void openExternalPublisher() {

        try {
            if (publisher == null) {
                publisher = PublisherFactory.getInstance().getPublisher(properties.getProperty("publisher.impl"));

                // don't trust the publisher will not make
                // any changes in the properties
                // actually IBM Watson IoT client does
                Properties copy = new Properties();
                copy.putAll(properties);

                publisher.connect(copy);
            }
        } catch (IOException ioe) {
            logger.severe(String.format("connection with the external publisher could not be established: %s", ioe.getMessage()));
            publisher = null;
        }
    }

    private void closeExternalPublisher() {

        try {
            if (publisher != null) {
                publisher.close();
            }
        } catch (IOException ioe) {
            logger.severe(String.format("connection with the external publisher could not be closed: %s", ioe.getMessage()));
        } finally {
            publisher = null;
        }
    }
}
