/**
 * 
 */
package com.android.contacts.activities;

import java.util.ArrayList;
import java.util.regex.Pattern;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;

/**
 * @author shade
 *
 */
public class T9Search {

    private final static String LOWER = "LOWER(" + Contacts.DISPLAY_NAME + ")";
    private final static String PEOPLE_QUERY = 
            "(" + LOWER + " GLOB ? OR " + LOWER + " GLOB ?) AND " + Contacts.HAS_PHONE_NUMBER + " = 1";
    private static final String[] PEOPLE_PROJECTION = 
            new String[] { Contacts._ID, Contacts.DISPLAY_NAME, Contacts.LOOKUP_KEY, Contacts.PHOTO_THUMBNAIL_URI};
    private static final String PEOPLE_SORT = Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC"; 
    private static final String[] PHONE_PROJECTION = 
            new String[] { Phone._ID, Contacts.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.PHOTO_THUMBNAIL_URI };
    private static final String PHONE_ID_QUERY = Phone.CONTACT_ID + " = ?";
    private static final String PHONE_QUERY = Phone.NUMBER + " GLOB ?";
    private static final String PHONE_QUERY_SORT = Phone.IS_SUPER_PRIMARY + " desc";

    private Context mContext;

    public T9Search(Context context) {
        mContext = context;
    }

    public class T9SearchResult {
        private final int numResults;
        private final String name;
        private final String number;
        private final ArrayList<ContactItem> mResults;
        public T9SearchResult (int numResults, String name, String number, ArrayList<ContactItem> results) {
            this.numResults = numResults;
            this.name = name;
            this.number = number;
            this.mResults = results;
        }
        public int getNumResults() {
            return numResults;
        }
        public ArrayList<ContactItem> getResults() {
            return mResults;
        }
        public String getName() {
            return name;
        }
        public String getNumber() {
            return number;
        }
    }

    public static class ContactItem {
        public String photoUri;
        public String name;
        public String number;
    }

    public T9SearchResult search(String number) {
        int numResults = 0;
        long lastId = 0;
        String bestName = null;
        String bestNumber = null;
        String bestUri = null;
        T9SearchResult result = null;
        ArrayList<ContactItem> allResults = new ArrayList<ContactItem>();

        if(!Pattern.matches("[a-zA-Z]+", number)) { 
            Cursor c = searchPhones(number);
            number = number.replaceAll("-","");
            try {
                while (c.moveToNext()) {
                    numResults++;
                    bestName = c.getString(1);
                    bestNumber = c.getString(2);
                    ContactItem temp = new ContactItem();
                    temp.name = bestName;
                    temp.number = bestNumber;
                    temp.photoUri = c.getString(4);
                    allResults.add(temp);
                }
            } finally {
                c.close();
            }
        }else{
            Cursor c = searchContacts(number);
            try {
                while (c.moveToNext()) {
                    numResults++;
                    bestName = c.getString(1);
                    bestNumber = getBestPhone(c.getString(0));
                    ContactItem temp = new ContactItem();
                    temp.name = bestName;
                    temp.number = bestNumber;
                    temp.photoUri = c.getString(3);
                    allResults.add(temp);
                }
            } finally {
                c.close();
            }
        }
        if (numResults > 0) {
            result = new T9SearchResult(numResults, bestName, bestNumber, allResults);
        }
        return result;
    }

    private String getBestPhone(String contactId) {
        String phone = null;
        Cursor c = mContext.getContentResolver().query(Phone.CONTENT_URI, 
                PHONE_PROJECTION,
                PHONE_ID_QUERY,
                new String[] { contactId }, 
                PHONE_QUERY_SORT);
        try {
            if (c.moveToFirst()) {
                phone = c.getString(2);
            }
        } finally {
            c.close();
        }
        return phone;
    }

    private Cursor searchPhones(String number) {
        Uri contactUri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(number));
        Cursor c = mContext.getContentResolver().query(contactUri, PHONE_PROJECTION, null,
                null, PHONE_QUERY_SORT);
        return c;
        //return mContext.getContentResolver().query(Phone.CONTENT_URI,
          //      PHONE_PROJECTION, 
            //    PHONE_QUERY,
              //  new String[] { number + "*" },
                //PHONE_QUERY_SORT);
    }

    private Cursor searchContacts(String number) {
        String matcher = buildT9ContactQuery(number);
        return mContext.getContentResolver().query(Contacts.CONTENT_URI, 
                PEOPLE_PROJECTION,
                PEOPLE_QUERY, 
                new String[] { matcher + "*", "*[ ]" + matcher + "*" }, 
                PEOPLE_SORT);
    }

    private String buildT9ContactQuery(String number) {
        StringBuilder sb = new StringBuilder();
        if (number != null) {
            for (int i = 0; i < number.length(); i++) {
                char key = number.charAt(i);
                if (! "-".equals(key)) {
                    sb.append(numberToRegexPart(key));
                }
            }
        }
        return sb.toString();
    }

    private String numberToRegexPart(char c) {
        switch (c) {
        case '2':
            return "[2abc]";
        case '3':
            return "[3def]";
        case '4':
            return "[4ghi]";
        case '5':
            return "[5jkl]";
        case '6':
            return "[6mno]";
        case '7':
            return "[7pqrs]";
        case '8':
            return "[8tuv]";
        case '9':
            return "[9wxyz]";
        case '*':
            return "?";
        default:
            return String.valueOf(c);
        }
    }
}