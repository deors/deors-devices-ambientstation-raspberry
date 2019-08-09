package deors.devices.ambientstation.raspberry;

import java.io.IOException;

import org.iot.raspberry.grovepi.GroveAnalogPin;
import org.iot.raspberry.grovepi.GrovePi;
import org.iot.raspberry.grovepi.GroveUtil;
import org.iot.raspberry.grovepi.devices.GroveAnalogInputDevice;

@GroveAnalogPin
public class GroveAirQualitySensor extends GroveAnalogInputDevice<Double> {

    public GroveAirQualitySensor(GrovePi grovePi, int pin) throws IOException {
        super(grovePi.getAnalogIn(pin, 4));
    }

    @Override
    public Double get(byte[] data) {
        int[] v = GroveUtil.unsign(data);
        return (double) (v[1] * 256) + v[2];
    }
}
