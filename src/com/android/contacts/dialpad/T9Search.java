/**
 * 
 */
package com.android.contacts.dialpad;

import java.util.ArrayList;
import java.util.regex.Pattern;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;

/**
 * @author shade,Danesh
 *
 */
class T9Search {

    private final static String LOWER = "LOWER(" + Contacts.DISPLAY_NAME + ")";
    private final static String PEOPLE_QUERY = 
            "(" + LOWER + " GLOB ? OR " + LOWER + " GLOB ?) AND " + Contacts.HAS_PHONE_NUMBER + " = 1";
    private static final String[] PEOPLE_PROJECTION = 
            new String[] { Contacts._ID, Contacts.DISPLAY_NAME, Contacts.LOOKUP_KEY, Contacts.PHOTO_THUMBNAIL_URI};
    private static final String PEOPLE_SORT = Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC"; 
    private static final String[] PHONE_PROJECTION = 
            new String[] { Phone._ID, Contacts.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.PHOTO_THUMBNAIL_URI };
    private static final String PHONE_ID_QUERY = Phone.CONTACT_ID + " = ?";
    private static final String PHONE_QUERY = Phone.NORMALIZED_NUMBER + " GLOB ?";
    private static final String PHONE_QUERY_SORT = Phone.IS_SUPER_PRIMARY + " desc";

    private Context mContext;

    public T9Search(Context context) {
        mContext = context;
    }

    public class T9SearchResult {
        private final ArrayList<ContactItem> mResults;
        private ContactItem mTopContact;
        public T9SearchResult (ArrayList<ContactItem> results) {
            mTopContact = new ContactItem();
            mTopContact.name = results.get(0).name;
            mTopContact.number = results.get(0).number;
            mTopContact.photoUri = results.get(0).photoUri;
            results.remove(0);
            this.mResults = results;
        }
        public int getNumResults() {
            return mResults.size()+1;
        }
        public String getTopName() {
            return mTopContact.name;
        }
        public String getTopNumber() {
            return mTopContact.number;
        }
        public String getTopPhoto() {
            return mTopContact.photoUri;
        }
        public ArrayList<ContactItem> getResults() {
            return mResults;
        }
    }

    protected static class ContactItem {
        String photoUri;
        String name;
        String number;
    }

    public T9SearchResult search(String number) {
        T9SearchResult result = null;
        ArrayList<ContactItem> allResults = new ArrayList<ContactItem>();

        Cursor c = searchPhones(number);
        try {
            while (c.moveToNext()) {
                ContactItem temp = new ContactItem();
                temp.name = c.getString(1);
                temp.number = c.getString(2);
                temp.photoUri = c.getString(4);
                allResults.add(temp);
            }
        } finally {
            c.close();
        }

        c = searchContacts(number);
        try {
            while (c.moveToNext()) {
                ContactItem temp = new ContactItem();
                temp.name = c.getString(1);
                temp.number = getBestPhone(c.getString(0));
                temp.photoUri = c.getString(3);
                if (!allResults.contains(temp)){
                    allResults.add(temp);
                }
            }
        } finally {
            c.close();
        }

        if (allResults.size() > 0) {
            result = new T9SearchResult(allResults);
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
        number=number.replaceAll( "[^\\d]", "" );
        return mContext.getContentResolver().query(Phone.CONTENT_URI,
                PHONE_PROJECTION,
                PHONE_QUERY,
                new String[] {"*" + number + "*"},
                PHONE_QUERY_SORT);
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