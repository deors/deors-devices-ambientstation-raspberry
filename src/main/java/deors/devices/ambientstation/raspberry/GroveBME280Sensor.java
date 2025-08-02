package deors.devices.ambientstation.raspberry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;
import com.pi4j.io.i2c.I2CFactory.UnsupportedBusNumberException;

import org.iot.raspberry.grovepi.pi4j.IO;

public class GroveBME280Sensor {

    private int iirFilter = 0;
    private int overscanHumidity = 1;
    private int overscanTemperature = 1;
    private int overscanPressure = 16;
    private int standbyTimeConstant = 2;
    private int operationMode = OPERATION_MODE_NORMAL;
    private double seaLevelPressure = 1013.25;
    private double tempIntermediate = 0;

    private double[] temperatureCoefficients = new double[3];
    private double[] pressureCoefficients = new double[9];
    private double[] humidityCoefficients = new double[6];

    private I2CBus bus;
    private I2CDevice dev;
    private IO io;

    private static final int defaultDeviceId = 0x76;
    private static final int chipId = 0x60;

    private static final int commandChipId = 0xD0;
    private static final int commandSoftReset = 0xE0;
    private static final int commandDigT1 = 0x88;
    private static final int commandDigH1 = 0xA1;
    private static final int commandDigH2 = 0xE1;
    private static final int commandDigH3 = 0xE3;
    private static final int commandDigH4 = 0xE4;
    private static final int commandDigH5 = 0xE5;
    private static final int commandDigH6 = 0xE7;
    private static final int commandCtrlHum = 0xF2;
    private static final int commandStatus = 0xF3;
    private static final int commandCtrlMeas = 0xF4;
    private static final int commandConfig = 0xF5;
    private static final int commandPressureData = 0xF7;
    private static final int commandTemperatureData = 0xFA;
    private static final int commandHumidityData = 0xFD;

    private static final int OPERATION_MODE_SLEEP = 0x00;
    private static final int OPERATION_MODE_FORCE = 0x01;
    private static final int OPERATION_MODE_NORMAL = 0x03;

    public GroveBME280Sensor() {
        super();
        try {
            init();
        } catch (UnsupportedBusNumberException | IOException e) {
            System.out.println("Sensor could not be initialized");
            e.printStackTrace();
            close();
        }
    }

    private void init() throws UnsupportedBusNumberException, IOException {
        bus = I2CFactory.getInstance(I2CBus.BUS_1);
        dev = bus.getDevice(0x76);
        io = new IO(dev);

        checkChipId();

        reset();

        readCoefficients();

        // set ctrl_hum and ctrl_meas
        // ctrl_hum = humidity oversampling
        io.write(commandCtrlHum, overscanHumidity);
        // ctrl_meas = the pressure and temperature data acquistion options
        io.write(commandCtrlMeas, overscanTemperature << 5 + overscanPressure << 2 + operationMode);

        // set config
        // writes may be ignored while in normal mode
        boolean normalFlag = false;
        if (operationMode == OPERATION_MODE_NORMAL) {
            normalFlag = true;
            operationMode = OPERATION_MODE_SLEEP;
            // write meas
        }
        int config = 0;
        if (operationMode == OPERATION_MODE_NORMAL) {
            config += standbyTimeConstant << 5;
        }
        config += iirFilter << 2;
        io.write(commandConfig, config);
        if (normalFlag) {
            operationMode = OPERATION_MODE_NORMAL;
            // write meas
        }
    }

    private void checkChipId() throws IOException {
        io.write(commandChipId);
        int readChipId = io.read();
        if (readChipId != chipId) {
            throw new IOException("Sensor chip id does not belong to BME680");
        }
    }

    private void reset() throws IOException {
        io.write(commandSoftReset, 0xB6);
        sleep(4);
    }

    private void readCoefficients() throws IOException {
        io.write(commandDigT1);
        byte[] coefficientData1 = new byte[24];
        io.read(coefficientData1);
        ByteBuffer bb1 = ByteBuffer.wrap(coefficientData1);
        bb1.order(ByteOrder.LITTLE_ENDIAN);
        temperatureCoefficients[0] = (int) bb1.getShort() & 0xffff;
        temperatureCoefficients[1] = bb1.getShort();
        temperatureCoefficients[2] = bb1.getShort();
        pressureCoefficients[0] = (int) bb1.getShort() & 0xffff;
        pressureCoefficients[1] = bb1.getShort();
        pressureCoefficients[2] = bb1.getShort();
        pressureCoefficients[3] = bb1.getShort();
        pressureCoefficients[4] = bb1.getShort();
        pressureCoefficients[5] = bb1.getShort();
        pressureCoefficients[6] = bb1.getShort();
        pressureCoefficients[7] = bb1.getShort();
        pressureCoefficients[8] = bb1.getShort();
        System.out.println("temperatureCoefficients: " + Arrays.toString(temperatureCoefficients));
        System.out.println("pressureCoefficients: " + Arrays.toString(pressureCoefficients));

        io.write(commandDigH1);
        humidityCoefficients[0] = io.read();

        io.write(commandDigH2);
        byte[] coefficientData2 = new byte[7];
        io.read(coefficientData2);
        ByteBuffer bb2 = ByteBuffer.wrap(coefficientData2);
        bb2.order(ByteOrder.LITTLE_ENDIAN);
        humidityCoefficients[1] = bb2.getShort();
        humidityCoefficients[2] = bb2.get();
        byte hc2 = bb2.get();
        byte hc3 = bb2.get();
        byte hc4 = bb2.get();
        humidityCoefficients[3] = (hc2 << 4) | (hc3 & 0xF);
        humidityCoefficients[4] = (hc4 << 4) | (hc3 >> 4);
        humidityCoefficients[5] = bb2.get();
        System.out.println("humidityCoefficients: " + Arrays.toString(humidityCoefficients));
    }

    private void close() {
        if (bus != null) {
            try {
                bus.close();
            } catch (IOException e) {
                System.out.println("Unable to close connection with sensor");
                e.printStackTrace();
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.currentThread().sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private int getStatus() throws IOException {
        io.write(commandStatus);
        return io.read();
    }

    private double getTemperature() throws IOException {
        // change mode to force?
        io.write(commandTemperatureData);
        byte[] tempData = new byte[3];
        io.read(tempData);
        int tempRaw = 0;
        for (byte b : tempData) {
            tempRaw *= 256;
            tempRaw += b & 0xFFFF;
        }
        tempRaw /= 16;
        double var1 = (tempRaw / 16384.0 - temperatureCoefficients[0] / 1024.0) * temperatureCoefficients[1];
        double var2 = Math.pow(tempRaw / 131072.0 - temperatureCoefficients[0] / 8192.0, 2) * temperatureCoefficients[2];
        tempIntermediate = (int) var1 + var2;
        return tempIntermediate / 5125.0;
    }

    public static void main(String[] args) {
        GroveBME280Sensor sensor = null;
        try {
            sensor = new GroveBME280Sensor();
            System.out.println(sensor.getTemperature());
        } catch (IOException e) {
            System.out.println("Unable to read temperature");
            e.printStackTrace();
        } finally {
            if (sensor != null) {
                sensor.close();
            }
        }
    }
}
