/*
 *  Copyright (C) 2016 The MoKee Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mokee.yahooweatherprovider;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import mokee.providers.WeatherContract;
import mokee.weather.MKWeatherManager;
import mokee.weather.RequestInfo;
import mokee.weather.WeatherInfo;
import mokee.weather.WeatherInfo.DayForecast;
import mokee.weather.WeatherLocation;
import mokee.weatherservice.ServiceRequest;
import mokee.weatherservice.ServiceRequestResult;
import mokee.weatherservice.WeatherProviderService;

public class YahooWeatherProviderService extends WeatherProviderService {

    private Context mContext;

    private static final String TAG = YahooWeatherProviderService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int FORECAST_DAYS = 4;

    private static final String URL_WEATHER =
            "https://query.yahooapis.com/v1/public/yql?format=xml&q=";
    private static final String URL_WEATHER_PARAMS =
            "select * from weather.forecast where woeid = %s and u= '%s'";

    private static final String URL_LOCATION =
            "https://query.yahooapis.com/v1/public/yql?format=json&q=" +
            Uri.encode("select woeid, postal, admin1, admin2, admin3, " +
                    "locality1, locality2, country from geo.places where " +
                    "(placetype = 7 or placetype = 8 or placetype = 9 " +
                    "or placetype = 10 or placetype = 11 or placetype = 20) and text =");

    private static final String URL_PLACEFINDER =
            "https://query.yahooapis.com/v1/public/yql?format=json&q=" +
            Uri.encode("select * from geo.places where " +
                    "text =");

    private static final String[] LOCALITY_NAMES = new String[] {
        "locality1", "locality2", "admin3", "admin2", "admin1"
    };

    private Map<ServiceRequest,WeatherUpdateRequestTask> mWeatherUpdateRequestMap = new HashMap<>();
    private Map<ServiceRequest,LookupCityNameRequestTask> mLookupCityRequestMap = new HashMap<>();

    //OpenWeatherMap recommends to wait 10 min between requests
    private final static long REQUEST_THRESHOLD = 1000L * 60L * 10L;
    private long mLastRequestTimestamp = -REQUEST_THRESHOLD;
    private WeatherLocation mLastWeatherLocation;
    private Location mLastLocation;
    //5km of threshold, the weather won't change that much in such short distance
    private static final float LOCATION_DISTANCE_METERS_THRESHOLD = 5f * 1000f;

    @Override
    public void onCreate() {
        mContext = getApplicationContext();
    }

    @Override
    protected void onRequestSubmitted(ServiceRequest request) {
        RequestInfo requestInfo = request.getRequestInfo();
        int requestType = requestInfo.getRequestType();
        if (DEBUG) Log.d(TAG, "Received request type " + requestType);

        if (((requestType == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ &&
                isSameGeoLocation(requestInfo.getLocation(), mLastLocation))
                    || (requestType == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ &&
                        isSameWeatherLocation(requestInfo.getWeatherLocation(),
                                mLastWeatherLocation))) && requestSubmittedTooSoon()) {
            request.reject(MKWeatherManager.RequestStatus.SUBMITTED_TOO_SOON);
            return;
        }

        switch (requestType) {
            case RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ:
            case RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ:
                synchronized (mWeatherUpdateRequestMap) {
                    WeatherUpdateRequestTask weatherTask = new WeatherUpdateRequestTask(request);
                    mWeatherUpdateRequestMap.put(request, weatherTask);
                    mLastRequestTimestamp = SystemClock.elapsedRealtime();
                    weatherTask.execute();
                }
                break;
            case RequestInfo.TYPE_LOOKUP_CITY_NAME_REQ:
                synchronized (mLookupCityRequestMap) {
                    LookupCityNameRequestTask lookupTask = new LookupCityNameRequestTask(request);
                    mLookupCityRequestMap.put(request, lookupTask);
                    lookupTask.execute();
                }
                break;
        }
    }

    private boolean requestSubmittedTooSoon() {
        final long now = SystemClock.elapsedRealtime();
        if (DEBUG) Log.d(TAG, "Now " + now + " last request " + mLastRequestTimestamp);
        return (mLastRequestTimestamp + REQUEST_THRESHOLD > now);
    }
    
    private class WeatherUpdateRequestTask extends AsyncTask<Void, Void, WeatherInfo> {
        final ServiceRequest mRequest;
        public WeatherUpdateRequestTask(ServiceRequest request) {
            mRequest = request;
        }

        public WeatherInfo getWeatherInfo(Location location, boolean metric) {
            String language = getLanguageCode();
            String locationParams = String.format(Locale.US, "\"(%f,%f)\" and lang=\"%s\"",
                    location.getLatitude(), location.getLongitude(), language);
            String url = URL_PLACEFINDER + Uri.encode(locationParams);
            JSONObject results = fetchResults(url);
            if (results == null) {
                return null;
            }
            try {
                JSONObject place = results.getJSONObject("place");
                WeatherLocation result = parsePlace(place);
                String woeid = null;
                String city = null;
                if (result != null) {
                    woeid = result.getCityId();
                    city = result.getCity();
                }
                // The city name in the placefinder result is HTML encoded :-(
                if (city != null) {
                    city = Html.fromHtml(city).toString();
                } else {
                    if (DEBUG) Log.w(TAG, "Can not resolve place name for " + location);
                }

                if (DEBUG) Log.d(TAG, "Resolved location " + location + " to " + city + " (" + woeid + ")");

                // woeid, city, metric
                WeatherInfo.Builder weatherInfo = getWeatherInfo(woeid, city, true);
                if (weatherInfo != null) {
                    return weatherInfo.build();
                }
            } catch (JSONException e) {
                if (DEBUG) Log.e(TAG, "Received malformed placefinder data (location="
                        + location + ", lang=" + language + ")", e);
            }
            return null;
        }

        public WeatherInfo.Builder getWeatherInfo(String id, String localizedCityName, boolean metric) {
            String url = URL_WEATHER + Uri.encode(String.format(URL_WEATHER_PARAMS, id, metric ? "c" : "f"));
            String response = HttpRetriever.retrieve(url);

            if (response == null) {
                return null;
            }

            SAXParserFactory factory = SAXParserFactory.newInstance();
            try {
                SAXParser parser = factory.newSAXParser();
                StringReader reader = new StringReader(response);
                WeatherHandler handler = new WeatherHandler();
                parser.parse(new InputSource(reader), handler);

                if (handler.isComplete()) {
                    // There are cases where the current condition is unknown, but the forecast
                    // is not - using the (inaccurate) forecast is probably better than showing
                    // the question mark
                    if (handler.conditionCode == 3200) {
                        handler.conditionCode = handler.forecasts.get(0).getConditionCode();
                    }

                    WeatherInfo.Builder weatherInfo = new WeatherInfo.Builder(
                            localizedCityName != null ? localizedCityName : handler.city, handler.temperature,
                                    metric ? WeatherContract.WeatherColumns.TempUnit.CELSIUS : WeatherContract.WeatherColumns.TempUnit.FAHRENHEIT);
                    weatherInfo.setHumidity(handler.humidity);
                    weatherInfo.setWind(handler.windSpeed, handler.windDirection, handler.speedUnit.equals("km/h")
                            ? WeatherContract.WeatherColumns.WindSpeedUnit.KPH : WeatherContract.WeatherColumns.WindSpeedUnit.MPH);
                    weatherInfo.setTodaysLow(handler.forecasts.get(0).getLow());
                    weatherInfo.setTodaysHigh(handler.forecasts.get(0).getHigh());
                    //NOTE: The timestamp provided by YahooWeather corresponds to the time the data
                    //was last updated by the stations. Let's use System.currentTimeMillis instead
                    weatherInfo.setTimestamp(System.currentTimeMillis());
                    weatherInfo.setWeatherCondition(handler.forecasts.get(0).getConditionCode());
                    weatherInfo.setForecast(handler.forecasts);

                    if (mRequest.getRequestInfo().getRequestType()
                            == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ) {
                        mLastWeatherLocation = mRequest.getRequestInfo().getWeatherLocation();
                        mLastLocation = null;
                    } else if (mRequest.getRequestInfo().getRequestType()
                            == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ) {
                        mLastLocation = mRequest.getRequestInfo().getLocation();
                        mLastWeatherLocation = null;
                    }

                    if (DEBUG) Log.d(TAG, "Weather updated: " + weatherInfo);
                    return weatherInfo;
                } else {
                    if (DEBUG) Log.w(TAG, "Received incomplete weather XML (id=" + id + ")");
                }
            } catch (ParserConfigurationException e) {
                if (DEBUG) Log.e(TAG, "Could not create XML parser", e);
            } catch (SAXException e) {
                if (DEBUG) Log.e(TAG, "Could not parse weather XML (id=" + id + ")", e);
            } catch (IOException e) {
                if (DEBUG) Log.e(TAG, "Could not parse weather XML (id=" + id + ")", e);
            }
            return null;
        }

        @Override
        protected WeatherInfo doInBackground(Void... params) {
            if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ) {
                return getWeatherInfo(mRequest.getRequestInfo().getWeatherLocation().getCityId(),
                        mRequest.getRequestInfo().getWeatherLocation().getCity(), true).build();
            } else if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ) {
                return getWeatherInfo(mRequest.getRequestInfo().getLocation(), true);
            } else {
                return null;
            }
        }

        @Override
        protected void onPostExecute(WeatherInfo weatherInfo) {
            if (weatherInfo == null) {
                if (DEBUG) Log.d(TAG, "Received null weather info, failing request");
                mRequest.fail();
            } else {
                if (DEBUG) Log.d(TAG, weatherInfo.toString());
                ServiceRequestResult result = new ServiceRequestResult.Builder(weatherInfo).build();
                mRequest.complete(result);
            }
        }

        private class WeatherHandler extends DefaultHandler {
            String city;
            String temperatureUnit, speedUnit;
            int windDirection, conditionCode;
            float humidity, temperature, windSpeed;
            ArrayList<DayForecast> forecasts = new ArrayList<DayForecast>();

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes)
                    throws SAXException {
                if (qName.equals("yweather:location")) {
                    city = attributes.getValue("city");
                } else if (qName.equals("yweather:units")) {
                    temperatureUnit = attributes.getValue("temperature");
                    speedUnit = attributes.getValue("speed");
                } else if (qName.equals("yweather:wind")) {
                    windDirection = (int) stringToFloat(attributes.getValue("direction"), -1);
                    windSpeed = stringToFloat(attributes.getValue("speed"), -1);
                } else if (qName.equals("yweather:atmosphere")) {
                    humidity = stringToFloat(attributes.getValue("humidity"), -1);
                } else if (qName.equals("yweather:condition")) {
                    conditionCode = (int) stringToFloat(attributes.getValue("code"), -1);
                    temperature = stringToFloat(attributes.getValue("temp"), Float.NaN);
                } else if (qName.equals("yweather:forecast")) {
                    DayForecast day = new DayForecast.Builder(conditionCode)
                        .setLow(stringToDouble(attributes.getValue("low"), Double.NaN))
                        .setHigh(stringToDouble(attributes.getValue("high"), Double.NaN)).build();
                    if (!Double.isNaN(day.getLow()) && !Double.isNaN(day.getHigh()) && day.getConditionCode() >= 0) {
                        if (forecasts.size() < FORECAST_DAYS) {
                            forecasts.add(day);
                        }
                    }
                }
            }

            public boolean isComplete() {
                return temperatureUnit != null && speedUnit != null && conditionCode >= 0
                        && !Float.isNaN(temperature) && !forecasts.isEmpty();
            }

            private float stringToFloat(String value, float defaultValue) {
                try {
                    if (value != null) {
                        return Float.parseFloat(value);
                    }
                } catch (NumberFormatException e) {
                    // fall through to the return line below
                }
                return defaultValue;
            }

            private Double stringToDouble(String value, Double defaultValue) {
                try {
                    if (value != null) {
                        return Double.parseDouble(value);
                    }
                } catch (NumberFormatException e) {
                    // fall through to the return line below
                }
                return defaultValue;
            }
        }

    }

    private boolean isSameWeatherLocation(WeatherLocation newLocation,
            WeatherLocation oldLocation) {
        if (newLocation == null || oldLocation == null) return false;
        return (newLocation.getCityId().equals(oldLocation.getCityId())
                && newLocation.getCity().equals(oldLocation.getCity())
                && newLocation.getPostalCode().equals(oldLocation.getPostalCode())
                && newLocation.getCountry().equals(oldLocation.getCountry())
                && newLocation.getCountryId().equals(oldLocation.getCountryId()));
    }

    private boolean isSameGeoLocation(Location newLocation, Location oldLocation) {
        if (newLocation == null || oldLocation == null) return false;
        float distance = newLocation.distanceTo(oldLocation);
        if (DEBUG) Log.d(TAG, "Distance between locations " + distance);
        return (distance < LOCATION_DISTANCE_METERS_THRESHOLD);
    }

    private class LookupCityNameRequestTask
            extends AsyncTask<Void, Void, ArrayList<WeatherLocation>> {

        final ServiceRequest mRequest;
        public LookupCityNameRequestTask(ServiceRequest request) {
            mRequest = request;
        }

        @Override
        protected ArrayList<WeatherLocation> doInBackground(Void... params) {
            ArrayList<WeatherLocation> locations = getLocations(
                    mRequest.getRequestInfo().getCityName());
            return locations;
        }

        @Override
        protected void onPostExecute(ArrayList<WeatherLocation> locations) {
            if (locations != null) {
                if (DEBUG) {
                    for (WeatherLocation location : locations) {
                        Log.d(TAG, location.toString());
                    }
                }
                ServiceRequestResult request = new ServiceRequestResult.Builder(locations).build();
                mRequest.complete(request);
            } else {
                mRequest.fail();
            }
        }

        private ArrayList<WeatherLocation> getLocations(String input) {
            String language = getLanguageCode();
            String params = "\"" + input + "\" and lang = \"" + language + "\"";
            String url = URL_LOCATION + Uri.encode(params);
            JSONObject jsonResults = fetchResults(url);
            if (jsonResults == null) {
                return null;
            }

            try {
                JSONArray places = jsonResults.optJSONArray("place");
                if (places == null) {
                    // Yahoo returns an object instead of an array when there's only one result
                    places = new JSONArray();
                    places.put(jsonResults.getJSONObject("place"));
                }

                ArrayList<WeatherLocation> results = new ArrayList<>();
                for (int i = 0; i < places.length(); i++) {
                    WeatherLocation weatherLocation = parsePlace(places.getJSONObject(i));
                    if (weatherLocation != null) {
                        results.add(weatherLocation);
                    }
                }
                return results;
            } catch (JSONException e) {
                if (DEBUG) Log.w(TAG, "JSONException while processing location lookup", e);
            }
            return null;
        }
    }

    @Override
    protected void onRequestCancelled(ServiceRequest request) {
        switch (request.getRequestInfo().getRequestType()) {
            case RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ:
            case RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ:
                synchronized (mWeatherUpdateRequestMap) {
                    WeatherUpdateRequestTask task = mWeatherUpdateRequestMap.remove(request);
                    if (task != null) {
                        task.cancel(true);
                    }
                    return;
                }
            case RequestInfo.TYPE_LOOKUP_CITY_NAME_REQ:
                synchronized (mLookupCityRequestMap) {
                    LookupCityNameRequestTask task = mLookupCityRequestMap.remove(request);
                    if (task != null) {
                        task.cancel(true);
                    }
                }
                return;
            default:
                Log.w(TAG, "Received unknown request type "
                        + request.getRequestInfo().getRequestType());
                break;
        }
    }

    private WeatherLocation parsePlace(JSONObject place) throws JSONException {
        JSONObject country = place.getJSONObject("country");

        String resultID = place.getString("woeid");
        String resultCountry = country.getString("content");
        String resultCountryId = country.getString("code");
        String resultCity = null;

        for (String name : LOCALITY_NAMES) {
            if (!place.isNull(name)) {
                JSONObject localeObject = place.getJSONObject(name);
                resultCity = localeObject.getString("content");
                if (localeObject.optString("woeid") != null) {
                    resultID = localeObject.getString("woeid");
                }
                break;
            }
        }

        if (DEBUG) Log.v(TAG, "JSON data " + place.toString() + " -> id=" + resultID
                    + ", city=" + resultCity + ", country=" + resultCountryId);

        if (resultID == null || resultCity == null || resultCountryId == null) {
            return null;
        }

        return new WeatherLocation.Builder(resultID, resultCity)
                .setCountry(resultCountry).setCountryId(resultCountryId).build();
    }

    private JSONObject fetchResults(String url) {
        String response = HttpRetriever.retrieve(url);
        if (response == null) {
            return null;
        }

        if (DEBUG) Log.v(TAG, "Request URL is " + url + ", response is " + response);

        try {
            JSONObject rootObject = new JSONObject(response);
            return rootObject.getJSONObject("query").getJSONObject("results");
        } catch (JSONException e) {
            if (DEBUG) Log.w(TAG, "Received malformed places data (url=" + url + ")", e);
        }

        return null;
    }

    private String getLanguageCode() {
        Locale locale = mContext.getResources().getConfiguration().locale;
        String country = locale.getCountry();
        String language = locale.getLanguage();

        if (TextUtils.isEmpty(country)) {
            return language;
        }
        return language + "-" + country;
    }

}