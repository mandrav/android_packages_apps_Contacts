package com.android.contacts.dialpad;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.android.contacts.R;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.telephony.PhoneNumberUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.QuickContactBadge;
import android.widget.TextView;

/**
 * @author shade,Danesh
 *
 */
class T9Search {

    //List sort modes
    private static final int NAME_FIRST = 1;
    private static final int NUMBER_FIRST = 2;
    private static final int DIRECT_NUMBER = 3;
    private Context mContext;
    private int mSortMode;

    //Phone number queries
    private static final String[] PHONE_PROJECTION = new String[] {Phone.NUMBER};
    private static final String PHONE_ID_SELECTION = Contacts.Data.MIMETYPE + " = ? ";
    private static final String[] PHONE_ID_SELECTION_ARGS = new String[] {Phone.CONTENT_ITEM_TYPE};
    private static final String[] CONTACT_PROJECTION = new String[] {Contacts._ID, Contacts.DISPLAY_NAME};
    private final static String CONTACT_QUERY = Contacts.HAS_PHONE_NUMBER + " > 0";

    //Local variables
    ArrayList<ContactItem> nameResults = new ArrayList<ContactItem>();
    ArrayList<ContactItem> numberResults = new ArrayList<ContactItem>();
    Set<ContactItem> allResults = new LinkedHashSet<ContactItem>();
    static ArrayList<ContactItem> contacts = new ArrayList<ContactItem>();

    public T9Search(Context context) {
        mContext = context;
        getAll();
    }

    void getAll() {
        Cursor c = mContext.getContentResolver().query(Contacts.CONTENT_URI, CONTACT_PROJECTION, CONTACT_QUERY, null, null);
        while (c.moveToNext()) {
            long contactId = c.getLong(0);
            for (String num : getPhone(String.valueOf(contactId))) {
                ContactItem contactInfo = new ContactItem();
                contactInfo.id = contactId;
                contactInfo.name = c.getString(1);
                contactInfo.number = PhoneNumberUtils.formatNumber(num);
                contactInfo.normalNumber = num.replaceAll( "[^\\d]", "" );
                contacts.add(contactInfo);
            }
        }
        c.close();
        Thread loadPics = new Thread(new Runnable() {
            public void run () {
                InputStream imageStream = null;
                Uri uri = null;
                for (ContactItem item : contacts) {
                    uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, item.id);
                    imageStream = ContactsContract.Contacts.openContactPhotoInputStream(mContext.getContentResolver(), uri);
                    if (imageStream != null) {
                        item.photo = Bitmap.createScaledBitmap(BitmapFactory.decodeStream(imageStream), 40, 40, false);
                    }
                }
            }
        });
        loadPics.setPriority(Thread.MIN_PRIORITY);
        loadPics.start();
    }

    public static class T9SearchResult {
        private final ArrayList<ContactItem> mResults;
        private ContactItem mTopContact = new ContactItem();
        public T9SearchResult (final ArrayList<ContactItem> results, final Context mContext) {
            mTopContact = results.get(0);
            mResults = results;
            mResults.remove(0);
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
        public Bitmap getTopPhoto() {
            return mTopContact.photo;
        }
        public ArrayList<ContactItem> getResults() {
            return mResults;
        }
    }

    protected static class ContactItem {
        Bitmap photo;
        String name;
        String number;
        String normalNumber;
        int matchId;
        long id;
    }

    public T9SearchResult search(String number) {
        nameResults.clear();
        numberResults.clear();
        allResults.clear();
        number=number.replaceAll( "[^\\d]", "" );
        int pos = 0;
        mSortMode = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(mContext).getString("t9_sort", "1"));
        //Go through each contact
        for (ContactItem item : contacts) {
            if (item.normalNumber.contains(number)) {
                item.matchId = item.normalNumber.indexOf(number);
                numberResults.add(item);
            }
            if ((pos = getNameMatchId(item.name,number)) != number.length()+1) {
                item.matchId = pos;
                nameResults.add(item);
            }
        }
        Collections.sort(numberResults, new CustomComparator());
        Collections.sort(nameResults, new CustomComparator());
        if (nameResults.size() > 0 || numberResults.size() > 0) {
            switch (mSortMode) {
            case NAME_FIRST:
                allResults.addAll(nameResults);
                allResults.addAll(numberResults);
                break;
            case NUMBER_FIRST:
            case DIRECT_NUMBER:
                allResults.addAll(numberResults);
                allResults.addAll(nameResults);
            }
            return new T9SearchResult(new ArrayList<ContactItem>(allResults), mContext);
        }
        return null;
    }

    public static class CustomComparator implements Comparator<ContactItem> {
        @Override
        public int compare(ContactItem lhs, ContactItem rhs) {
            return Integer.compare(lhs.matchId,rhs.matchId);
        }
    }

    private static int getNameMatchId(String name, String input) {
        Pattern pattern = Pattern.compile(buildT9ContactQuery(input),Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(name);
        if (m.find()) {
            return m.start();
        } else {
            return name.length()+1;
        }
    }

    private ArrayList<String> getPhone(String contactId) {
        Uri baseUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, Long.valueOf(contactId));
        Uri dataUri = Uri.withAppendedPath(baseUri, Contacts.Data.CONTENT_DIRECTORY);
        Cursor cursor = mContext.getContentResolver().query(dataUri,PHONE_PROJECTION,PHONE_ID_SELECTION,PHONE_ID_SELECTION_ARGS,null);
        ArrayList<String> allNums = new ArrayList<String>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                allNums.add(cursor.getString(0));
            }
            cursor.close();
            return allNums;
        }
        return null;
    }

    private static String buildT9ContactQuery(String number) {
        StringBuilder sb = new StringBuilder();
        if (number != null) {
            for (int i = 0; i < number.length(); i++) {
                char key = number.charAt(i);
                sb.append(numberToRegex(key));
            }
        }
        return sb.toString();
    }

    private static String numberToRegex(char c) {
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

    protected static class T9Adapter extends ArrayAdapter<ContactItem> {

        private ArrayList<ContactItem> items;
        private LayoutInflater menuInflate;
        private static ContactItem o;

        public T9Adapter(Context context, int textViewResourceId, ArrayList<ContactItem> items, LayoutInflater menuInflate) {
            super(context, textViewResourceId, items);
            this.items = items;
            this.menuInflate = menuInflate;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                convertView = menuInflate.inflate(R.layout.row, null);
                holder = new ViewHolder();
                holder.name = (TextView) convertView.findViewById(R.id.rowName);
                holder.number = (TextView) convertView.findViewById(R.id.rowNumber);
                holder.icon = (QuickContactBadge) convertView.findViewById(R.id.rowBadge);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            o = items.get(position);
            holder.name.setText(o.name);
            holder.number.setText(o.number);
            if (o.photo!=null) {
                holder.icon.setImageBitmap(o.photo);
            }else {
                holder.icon.setImageResource(R.drawable.ic_contact_picture_180_holo_dark);
            }
            holder.icon.assignContactFromPhone(o.number, true);
            return convertView;
        }

        static class ViewHolder {
            TextView name,number;
            QuickContactBadge icon;
        }
    }
}